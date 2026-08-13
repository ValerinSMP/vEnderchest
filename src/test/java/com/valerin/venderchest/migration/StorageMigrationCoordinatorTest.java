package com.valerin.venderchest.migration;

import com.valerin.venderchest.storage.SqliteToMysqlMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageMigrationCoordinatorTest {

    @TempDir Path temp;

    @Test
    void startRejectsActiveServerBeforeMaintenanceOrSourceClose() throws Exception {
        Fixture fixture = fixture("reject");
        fixture.environment.rejection = "Hay jugadores online.";
        AtomicReference<StorageMigrationCoordinator.Result> result = new AtomicReference<>();

        assertTrue(fixture.coordinator.start(result::set));
        fixture.worker.drain();

        assertFalse(result.get().success());
        assertEquals("Hay jugadores online.", result.get().message());
        assertFalse(fixture.environment.maintenance);
        assertFalse(fixture.environment.sourceClosed);
    }

    @Test
    void publishesMaintenanceBeforeClosingSourceAndCopiesAsync() throws Exception {
        Fixture fixture = fixture("start");
        AtomicReference<StorageMigrationCoordinator.Result> result = new AtomicReference<>();

        assertTrue(fixture.coordinator.start(result::set));
        assertNull(result.get());
        fixture.worker.runOne(); // preflight -> main admission -> queues copy
        assertTrue(fixture.environment.maintenance);
        assertTrue(fixture.environment.sourceClosed);
        assertEquals(List.of("maintenance", "close"), fixture.environment.events);
        assertNull(result.get());

        fixture.worker.drain();
        assertTrue(result.get().success());
        assertEquals(SqliteToMysqlMigration.Status.COMPLETED, fixture.coordinator.status().status());
    }

    @Test
    void onlyOneGlobalOperationRunsAndShutdownSuppressesCallbacks() throws Exception {
        Fixture fixture = fixture("global");
        AtomicReference<StorageMigrationCoordinator.Result> result = new AtomicReference<>();
        assertTrue(fixture.coordinator.dryRun(result::set));
        assertFalse(fixture.coordinator.start(ignored -> {}));
        fixture.coordinator.close();
        fixture.worker.drain();
        assertNull(result.get());
    }

    private Fixture fixture(String name) throws Exception {
        Path source = temp.resolve(name + ".db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ec_pages (uuid VARCHAR(36), page TINYINT, data TEXT, revision BIGINT, PRIMARY KEY(uuid,page))");
            statement.execute("CREATE TABLE ec_extra (uuid VARCHAR(36) PRIMARY KEY, extra INTEGER)");
            statement.execute("CREATE TABLE ec_migrated (uuid VARCHAR(36), type VARCHAR(32), PRIMARY KEY(uuid,type))");
            statement.execute("INSERT INTO ec_pages VALUES ('11111111-1111-1111-1111-111111111111',1,'[null]',4)");
        }
        String jdbc = "jdbc:h2:mem:coordinator_" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        SqliteToMysqlMigration migration = new SqliteToMysqlMigration(source, "ec_",
                new SqliteToMysqlMigration.Destination(
                        jdbc, "sa", "", "h2/" + name, "dst_", false),
                temp.resolve(name + ".json"));
        FakeEnvironment environment = new FakeEnvironment();
        ManualExecutorService worker = new ManualExecutorService();
        StorageMigrationCoordinator coordinator = new StorageMigrationCoordinator(
                migration, environment, worker);
        return new Fixture(coordinator, environment, worker);
    }

    private record Fixture(StorageMigrationCoordinator coordinator,
                           FakeEnvironment environment, ManualExecutorService worker) {}

    private static final class FakeEnvironment implements StorageMigrationCoordinator.Environment {
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();
        private String rejection;
        private boolean maintenance;
        private boolean sourceClosed;

        @Override public void runMain(Runnable task) { task.run(); }
        @Override public boolean pluginEnabled() { return true; }
        @Override public boolean sourceActive() { return !maintenance; }
        @Override public String admissionRejection() { return rejection; }
        @Override public boolean enterMaintenance() {
            events.add("maintenance");
            maintenance = true;
            return true;
        }
        @Override public void closeSourceStorage() {
            if (!maintenance) throw new AssertionError("source closed before maintenance");
            events.add("close");
            sourceClosed = true;
        }
    }

    private static final class ManualExecutorService extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }
        private void runOne() { tasks.remove().run(); }
        private void drain() { while (!tasks.isEmpty()) runOne(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
