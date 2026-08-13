package com.valerin.venderchest.migration;

import com.valerin.venderchest.storage.SqliteToMysqlMigration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns the one offline SQLite-to-MySQL copy allowed during this plugin process. */
public final class StorageMigrationCoordinator implements AutoCloseable {

    private final SqliteToMysqlMigration migration;
    private final Environment environment;
    private final ExecutorService worker;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public StorageMigrationCoordinator(
            SqliteToMysqlMigration migration, Environment environment) {
        this(migration, environment, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "vEnderchest-storage-migration");
            thread.setDaemon(true);
            return thread;
        }));
    }

    StorageMigrationCoordinator(
            SqliteToMysqlMigration migration, Environment environment, ExecutorService worker) {
        this.migration = migration;
        this.environment = environment;
        this.worker = worker;
    }

    public boolean dryRun(Consumer<Result> callback) {
        if (!begin()) return false;
        worker.execute(() -> {
            Result result;
            try {
                SqliteToMysqlMigration.Inspection inspection = migration.dryRun();
                result = Result.inspection(inspection, environment.sourceActive());
            } catch (Exception error) {
                result = Result.failed("No se pudo validar la fuente o el destino.");
            }
            finish(result, callback);
        });
        return true;
    }

    public boolean start(Consumer<Result> callback) {
        return migrate(SqliteToMysqlMigration.Mode.START, callback);
    }

    public boolean resume(Consumer<Result> callback) {
        return migrate(SqliteToMysqlMigration.Mode.RESUME, callback);
    }

    public SqliteToMysqlMigration.StatusReport status() {
        return migration.status();
    }

    public boolean isRunning() { return running.get(); }

    private boolean migrate(SqliteToMysqlMigration.Mode mode, Consumer<Result> callback) {
        if (!begin()) return false;
        SqliteToMysqlMigration.Status existing = migration.status().status();
        if ((mode == SqliteToMysqlMigration.Mode.START
                && existing != SqliteToMysqlMigration.Status.NOT_STARTED)
                || (mode == SqliteToMysqlMigration.Mode.RESUME
                && existing != SqliteToMysqlMigration.Status.RUNNING)) {
            finish(Result.failed(mode == SqliteToMysqlMigration.Mode.START
                    ? "Ya existe un checkpoint; usa status/resume según corresponda."
                    : "Resume exige un checkpoint RUNNING compatible."), callback);
            return true;
        }

        worker.execute(() -> {
            final SqliteToMysqlMigration.Inspection inspection;
            try {
                inspection = migration.dryRun();
            } catch (Exception error) {
                finish(Result.failed("Preflight falló; no se activó maintenance."), callback);
                return;
            }
            if (inspection.conflicts() > 0) {
                finish(Result.failed("El destino contiene filas diferentes; no se modificó nada."), callback);
                return;
            }
            environment.runMain(() -> admitAndRun(mode, inspection, callback));
        });
        return true;
    }

    private void admitAndRun(
            SqliteToMysqlMigration.Mode mode,
            SqliteToMysqlMigration.Inspection inspection,
            Consumer<Result> callback) {
        if (closed.get() || !environment.pluginEnabled()) {
            running.set(false);
            return;
        }
        String rejection = environment.admissionRejection();
        if (rejection != null) {
            finish(Result.failed(rejection), callback);
            return;
        }
        if (!environment.enterMaintenance()) {
            finish(Result.failed("No se pudo publicar MAINTENANCE."), callback);
            return;
        }

        // From this point until restart the active store remains closed, even on failure.
        environment.closeSourceStorage();
        worker.execute(() -> {
            Result result;
            try {
                SqliteToMysqlMigration.RunResult outcome = migration.run(mode, inspection.fingerprint());
                result = Result.run(outcome);
            } catch (Exception error) {
                result = Result.failed("La copia se detuvo; usa status y conserva la fuente.");
            }
            finish(result, callback);
        });
    }

    private boolean begin() {
        return !closed.get() && running.compareAndSet(false, true);
    }

    private void finish(Result result, Consumer<Result> callback) {
        running.set(false);
        if (closed.get()) return;
        environment.runMain(() -> {
            if (!closed.get() && environment.pluginEnabled()) callback.accept(result);
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow();
    }

    public interface Environment {
        void runMain(Runnable task);
        boolean pluginEnabled();
        boolean sourceActive();
        String admissionRejection();
        boolean enterMaintenance();
        void closeSourceStorage();
    }

    public record Result(boolean success, String message,
                         long owners, long pages, long bytes,
                         long missing, long equal, long conflicts,
                         boolean informational, String hash) {
        private static Result failed(String message) {
            return new Result(false, message, 0, 0, 0, 0, 0, 0, false, null);
        }

        private static Result inspection(
                SqliteToMysqlMigration.Inspection inspection, boolean sourceActive) {
            return new Result(true,
                    sourceActive
                            ? "Dry-run informativo: la fuente sigue activa y puede cambiar."
                            : "Dry-run completado.",
                    inspection.owners().size(), inspection.pages(), inspection.bytes(),
                    inspection.missing(), inspection.equal(), inspection.conflicts(),
                    sourceActive, inspection.fingerprint());
        }

        private static Result run(SqliteToMysqlMigration.RunResult outcome) {
            return new Result(outcome.status() == SqliteToMysqlMigration.Status.COMPLETED,
                    outcome.message(), 0, 0, 0, outcome.inserted(), outcome.skipped(),
                    outcome.status() == SqliteToMysqlMigration.Status.CONFLICT ? 1 : 0,
                    false, outcome.verifiedHash());
        }
    }
}
