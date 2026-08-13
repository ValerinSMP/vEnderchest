package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.config.CrossServerSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerLifecycleTest {

    private CrossServerLifecycle lifecycle;

    @AfterEach
    void close() {
        if (lifecycle != null) lifecycle.close();
    }

    @Test
    void invalidCandidateNeverReplacesActiveSnapshot() throws Exception {
        FakeFactory factory = new FakeFactory();
        lifecycle = new CrossServerLifecycle(factory);
        var original = valid("network-a", "ec_");
        assertTrue(lifecycle.start(original).get(2, TimeUnit.SECONDS).accepted());

        YamlConfiguration invalid = enabled("network-b", "bad-prefix!");
        var rejected = lifecycle.reload(CrossServerSettings.parse(invalid)).get(2, TimeUnit.SECONDS);

        assertFalse(rejected.accepted());
        assertSame(original.settings(), lifecycle.activeSettings());
        assertEquals(1, factory.opened.size());
    }

    @Test
    void legacyMutationJournalBlocksStartupWithoutDeletingIt() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.legacyMutation = true;
        lifecycle = new CrossServerLifecycle(factory);

        var result = lifecycle.start(valid("network-a", "ec_")).get(2, TimeUnit.SECONDS);

        assertFalse(result.accepted());
        assertEquals(CrossServerLifecycle.State.FAILED, lifecycle.state());
        assertTrue(result.reasons().getFirst().contains("non-terminal legacy mutation journal"));
        assertTrue(factory.opened.getFirst().closed);
    }

    @Test
    void sensitiveReloadIsRejectedWhileRuntimeHasState() throws Exception {
        FakeFactory factory = new FakeFactory();
        lifecycle = new CrossServerLifecycle(factory);
        var original = valid("network-a", "ec_");
        lifecycle.start(original).get(2, TimeUnit.SECONDS);
        factory.opened.getFirst().active = true;

        var result = lifecycle.reload(valid("network-b", "other_"))
                .get(2, TimeUnit.SECONDS);

        assertFalse(result.accepted());
        assertSame(original.settings(), lifecycle.activeSettings());
        assertFalse(factory.opened.getFirst().closed);
    }

    @Test
    void idleRuntimeCanBeAtomicallyReplacedOrDisabled() throws Exception {
        FakeFactory factory = new FakeFactory();
        lifecycle = new CrossServerLifecycle(factory);
        lifecycle.start(valid("network-a", "ec_")).get(2, TimeUnit.SECONDS);
        FakeRuntime first = factory.opened.getFirst();

        assertTrue(lifecycle.reload(valid("network-b", "ec_")).get(2, TimeUnit.SECONDS).accepted());
        assertTrue(first.closed);
        assertEquals("network-b", lifecycle.activeSettings().networkNamespace());

        YamlConfiguration single = new YamlConfiguration();
        single.set("database.type", "mysql");
        single.set("database.table-prefix", "ec_");
        assertTrue(lifecycle.reload(CrossServerSettings.parse(single)).get(2, TimeUnit.SECONDS).accepted());
        assertEquals(CrossServerLifecycle.State.SINGLE_SERVER, lifecycle.state());
        assertTrue(factory.opened.get(1).closed);
    }

    @Test
    void tablePrefixChangeRequiresRestartEvenWhenIdle() throws Exception {
        FakeFactory factory = new FakeFactory();
        lifecycle = new CrossServerLifecycle(factory);
        lifecycle.start(valid("network-a", "ec_")).get(2, TimeUnit.SECONDS);

        var result = lifecycle.reload(valid("network-a", "other_"))
                .get(2, TimeUnit.SECONDS);

        assertFalse(result.accepted());
        assertEquals("ec_", lifecycle.activeSettings().tablePrefix());
        assertEquals(1, factory.opened.size());
    }

    @Test
    void closeFromLifecycleExecutorDoesNotWaitForItself() throws Exception {
        AtomicReference<CrossServerLifecycle> reference = new AtomicReference<>();
        CountDownLatch ticked = new CountDownLatch(1);
        CrossServerLifecycle.RuntimeFactory factory = settings -> new CrossServerLifecycle.RuntimeHandle() {
            @Override public boolean hasActiveState() { return false; }
            @Override public void tick() {
                reference.get().close();
                ticked.countDown();
            }
            @Override public void close() {}
        };
        lifecycle = new CrossServerLifecycle(factory);
        reference.set(lifecycle);
        lifecycle.start(fastValid("network-a")).get(2, TimeUnit.SECONDS);

        assertTrue(ticked.await(2, TimeUnit.SECONDS));
        assertEquals(CrossServerLifecycle.State.CLOSED, lifecycle.state());
    }

    @Test
    void externalCloseIsBoundedAndStopsRenewCallbacks() throws Exception {
        CountDownLatch closeBlock = new CountDownLatch(1);
        CountingRuntime runtime = new CountingRuntime(closeBlock);
        lifecycle = new CrossServerLifecycle(settings -> runtime,
                Executors.newSingleThreadScheduledExecutor(), 100);
        lifecycle.start(fastValid("network-a")).get(2, TimeUnit.SECONDS);
        assertTrue(runtime.firstTick.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        lifecycle.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int ticksAtClose = runtime.ticks;
        Thread.sleep(350);

        assertTrue(elapsedMillis < 1_000);
        assertEquals(ticksAtClose, runtime.ticks);
        closeBlock.countDown();
    }

    @Test
    void replacementCancelsOldRenewTaskAndClosesOldRuntimeOnce() throws Exception {
        FakeFactory factory = new FakeFactory();
        lifecycle = new CrossServerLifecycle(factory);
        lifecycle.start(fastValid("network-a")).get(2, TimeUnit.SECONDS);
        FakeRuntime first = factory.opened.getFirst();
        assertTrue(first.firstTick.await(2, TimeUnit.SECONDS));

        assertTrue(lifecycle.reload(fastValid("network-b")).get(2, TimeUnit.SECONDS).accepted());
        int oldTicks = first.ticks;
        FakeRuntime second = factory.opened.get(1);
        assertTrue(second.firstTick.await(2, TimeUnit.SECONDS));
        Thread.sleep(350);

        assertEquals(oldTicks, first.ticks);
        assertEquals(1, first.closeCount);
        assertTrue(second.ticks >= 1);
    }

    @Test
    void providerExceptionDoesNotExposeSecretMessage() throws Exception {
        lifecycle = new CrossServerLifecycle(settings -> {
            throw new IllegalStateException("redis://user:super-secret@host");
        });

        var result = lifecycle.start(valid("network-a", "ec_")).get(2, TimeUnit.SECONDS);

        assertFalse(result.accepted());
        assertFalse(result.reasons().getFirst().contains("super-secret"));
    }

    private CrossServerSettings.Validation valid(String network, String prefix) {
        return CrossServerSettings.parse(enabled(network, prefix));
    }

    private CrossServerSettings.Validation fastValid(String network) {
        YamlConfiguration yaml = enabled(network, "ec_");
        yaml.set("cross-server.lease.ttl-ms", 3_000);
        yaml.set("cross-server.lease.renew-ms", 250);
        yaml.set("cross-server.lease.safety-ms", 1_000);
        yaml.set("cross-server.lease.mysql-seconds", 4);
        return CrossServerSettings.parse(yaml);
    }

    private YamlConfiguration enabled(String network, String prefix) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("database.type", "mysql");
        yaml.set("database.table-prefix", prefix);
        yaml.set("cross-server.enabled", true);
        yaml.set("cross-server.network", network);
        yaml.set("cross-server.server-id", "server-1");
        return yaml;
    }

    private static final class FakeFactory implements CrossServerLifecycle.RuntimeFactory {
        private final List<FakeRuntime> opened = new ArrayList<>();
        private boolean legacyMutation;

        @Override
        public CrossServerLifecycle.RuntimeHandle open(CrossServerSettings settings) {
            FakeRuntime runtime = new FakeRuntime();
            runtime.legacyMutation = legacyMutation;
            opened.add(runtime);
            return runtime;
        }
    }

    private static class FakeRuntime implements CrossServerLifecycle.RuntimeHandle {
        private boolean active;
        private boolean closed;
        private int closeCount;
        private boolean legacyMutation;
        volatile int ticks;
        final CountDownLatch firstTick = new CountDownLatch(1);

        @Override public boolean hasActiveState() { return active; }
        @Override public boolean hasLegacyMutationState() { return legacyMutation; }
        @Override public void tick() { ticks++; firstTick.countDown(); }
        @Override public void close() { closed = true; closeCount++; }
    }

    private static final class CountingRuntime extends FakeRuntime {
        private final CountDownLatch closeBlock;
        private CountingRuntime(CountDownLatch closeBlock) { this.closeBlock = closeBlock; }
        @Override public void close() {
            super.close();
            try { closeBlock.await(1, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}
