package com.valerin.venderchest.gui;

import com.valerin.venderchest.session.OpenAttempt;
import com.valerin.venderchest.session.SessionState;
import com.valerin.venderchest.session.VaultKey;
import com.valerin.venderchest.session.VaultSession;
import com.valerin.venderchest.session.VaultSessionRegistry;
import com.valerin.venderchest.storage.Storage;
import com.valerin.venderchest.model.OpenSession;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiManagerMainThreadTest {

    @Test
    void crossServerOpenRequiresAnEmptyCursor() {
        assertTrue(GuiManager.crossCursorEmpty(null));
        assertTrue(GuiManager.crossCursorEmpty(true, 1));
        assertTrue(GuiManager.crossCursorEmpty(false, 0));
        assertFalse(GuiManager.crossCursorEmpty(false, 1));
    }

    @Test
    void cacheHitOffMainDefersBukkitAccessExactlyOnce() {
        List<Runnable> scheduled = new ArrayList<>();
        VaultSessionRegistry registry = new VaultSessionRegistry();
        RecordingGuiManager manager = new RecordingGuiManager(registry, () -> false, scheduled::add);
        UUID owner = UUID.randomUUID();
        Player actor = player(owner);
        VaultSession session = ((OpenAttempt.Created) registry.beginOpen(owner, owner, "3")).session();
        manager.cacheLatest(new VaultKey(owner, "3"),
                new Storage.PageRecord(new ItemStack[45], 14));
        long request = manager.beginOpenRequest(owner);

        manager.proceedToLoad(actor, session, null, 3, false, request);

        assertEquals(0, manager.bukkitAccesses.get());
        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        assertEquals(1, manager.bukkitAccesses.get());
        assertEquals(0, scheduled.size());
    }

    @Test
    void asyncCompletionOffMainDefersBukkitAccessExactlyOnce() {
        List<Runnable> scheduled = new ArrayList<>();
        VaultSessionRegistry registry = new VaultSessionRegistry();
        RecordingGuiManager manager = new RecordingGuiManager(registry, () -> false, scheduled::add);
        UUID owner = UUID.randomUUID();
        Player actor = player(owner);
        VaultSession session = ((OpenAttempt.Created) registry.beginOpen(owner, owner, "3")).session();
        long request = manager.beginOpenRequest(owner);

        manager.applyLoadedRecordOnMain(actor, session, null, 3, false,
                new Storage.PageRecord(new ItemStack[45], 14), request);

        assertEquals(0, manager.bukkitAccesses.get());
        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        assertEquals(1, manager.bukkitAccesses.get());
        assertEquals(0, scheduled.size());
    }

    @Test
    void mainThreadRunsInlineWithoutScheduling() {
        List<Runnable> scheduled = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        GuiManager manager = new GuiManager(null, null, null, null, null, () -> true, scheduled::add);

        manager.runOnMainThread(executions::incrementAndGet);

        assertEquals(1, executions.get());
        assertEquals(0, scheduled.size());
    }

    @Test
    void olderDifferentPageCompletionCannotPublishAfterNewerActorRequest() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        RecordingGuiManager manager = new RecordingGuiManager(registry, () -> true, ignored -> { });
        UUID actorId = UUID.randomUUID();
        Player actor = player(actorId);
        VaultSession olderPage3 = ((OpenAttempt.Created) registry.beginOpen(actorId, actorId, "3")).session();
        long olderRequest = manager.beginOpenRequest(actorId);
        VaultSession newerPage2 = ((OpenAttempt.Created) registry.beginOpen(actorId, actorId, "2")).session();
        long newerRequest = manager.beginOpenRequest(actorId);

        // Page 3 was requested first, page 2 second. The newer page finishes first; the late
        // page-3 load must not publish another inventory over it.
        manager.applyLoadedRecordOnMain(actor, newerPage2, null, 2, false,
                new Storage.PageRecord(new ItemStack[45], 20), newerRequest);
        manager.applyLoadedRecordOnMain(actor, olderPage3, null, 3, false,
                new Storage.PageRecord(new ItemStack[45], 22), olderRequest);

        assertEquals(List.of(2), manager.publishedPages);
        assertEquals(SessionState.CLOSED, olderPage3.getState());
    }

    @Test
    void nextTickNeverRunsInlineInsideInventoryEvent() {
        List<Runnable> scheduled = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        GuiManager manager = new GuiManager(null, null, null, null, null, () -> true, scheduled::add);

        manager.runNextTick(executions::incrementAndGet);

        assertEquals(0, executions.get());
        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        assertEquals(1, executions.get());
    }

    @Test
    void previousSessionRemainsVisibleWhileBukkitOpensReplacement() {
        UUID actorId = UUID.randomUUID();
        GuiManager manager = new GuiManager(null, null, null, null, null, () -> true, ignored -> { });
        AtomicReference<GuiManager> managerRef = new AtomicReference<>(manager);
        List<OpenSession> observedDuringOpen = new ArrayList<>();
        Player actor = player(actorId, () -> observedDuringOpen.add(managerRef.get().getSession(actorId)));
        OpenSession first = OpenSession.mainMenu(inventory());
        OpenSession second = OpenSession.mainMenu(inventory());

        manager.publishOpenSession(actor, first);
        manager.publishOpenSession(actor, second);

        assertEquals(2, observedDuringOpen.size());
        assertEquals(null, observedDuringOpen.get(0));
        assertSame(first, observedDuringOpen.get(1));
        assertSame(second, manager.getSession(actorId));
    }

    private static Player player(UUID uuid) {
        return player(uuid, () -> { });
    }

    private static Player player(UUID uuid, Runnable onOpenInventory) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("openInventory")) onOpenInventory.run();
                    return defaultValue(method.getReturnType());
                });
    }

    private static Inventory inventory() {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static final class RecordingGuiManager extends GuiManager {
        private final AtomicInteger bukkitAccesses = new AtomicInteger();
        private final List<Integer> publishedPages = new ArrayList<>();

        private RecordingGuiManager(VaultSessionRegistry registry, BooleanSupplier primaryThread,
                                    Consumer<Runnable> scheduler) {
            super(null, null, null, registry, null, primaryThread, scheduler);
        }

        @Override
        void applyLoadedRecord(Player actor, VaultSession session, String ownerName, int page,
                               boolean adminView, Storage.PageRecord record) {
            bukkitAccesses.incrementAndGet();
            publishedPages.add(page);
        }
    }
}
