package com.valerin.venderchest.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteToMysqlMigrationTest {

    @TempDir Path temp;

    @Test
    void dryRunCountsLogicalRowsAndPerformsZeroWrites() throws Exception {
        Fixture fixture = fixture("dry", "src_", "custom_");

        SqliteToMysqlMigration.Inspection result = fixture.migration.dryRun();

        assertEquals(2, result.owners().size());
        assertEquals(2, result.pages());
        assertTrue(result.bytes() > 0);
        assertEquals(4, result.missing());
        assertEquals(0, result.conflicts());
        assertFalse(result.destinationSchemaPresent());
        assertFalse(Files.exists(fixture.checkpoint));
        try (Connection destination = DriverManager.getConnection(fixture.jdbc, "sa", "")) {
            assertFalse(tableExists(destination, "custom_pages"));
        }
    }

    @Test
    void startPreservesRevisionsExtrasFlagsAndCompletesWithIndependentHash() throws Exception {
        Fixture fixture = fixture("complete", "src_", "dst_");
        String fingerprint = fixture.migration.dryRun().fingerprint();

        SqliteToMysqlMigration.RunResult result = fixture.migration.run(
                SqliteToMysqlMigration.Mode.START, fingerprint);

        assertEquals(SqliteToMysqlMigration.Status.COMPLETED, result.status());
        assertNotNull(result.verifiedHash());
        assertEquals(SqliteToMysqlMigration.Status.COMPLETED, fixture.migration.status().status());
        try (Connection destination = DriverManager.getConnection(fixture.jdbc, "sa", "");
             var pages = destination.createStatement().executeQuery(
                     "SELECT COUNT(*), SUM(revision) FROM dst_pages")) {
            assertTrue(pages.next());
            assertEquals(2, pages.getInt(1));
            assertEquals(16, pages.getLong(2));
            assertEquals(1, scalar(destination, "SELECT extra FROM dst_extra"));
            assertEquals(1, scalar(destination, "SELECT COUNT(*) FROM dst_migrated"));
        }
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + fixture.source)) {
            assertEquals(2, scalar(source, "SELECT COUNT(*) FROM src_pages"));
        }
    }

    @Test
    void compatiblePartialDestinationSchemaIsValidatedAndCompletedIdempotently() throws Exception {
        Fixture fixture = fixture("partial", "src_", "dst_");
        try (Connection destination = DriverManager.getConnection(fixture.jdbc, "sa", "")) {
            destination.createStatement().execute(
                    "CREATE TABLE dst_pages (uuid VARCHAR(36), page TINYINT, data MEDIUMTEXT, revision BIGINT, PRIMARY KEY(uuid,page))");
        }

        SqliteToMysqlMigration.Inspection inspection = fixture.migration.dryRun();
        assertTrue(inspection.destinationSchemaPresent());
        assertEquals(4, inspection.missing());
        assertEquals(SqliteToMysqlMigration.Status.COMPLETED,
                fixture.migration.run(SqliteToMysqlMigration.Mode.START, inspection.fingerprint()).status());
        try (Connection destination = DriverManager.getConnection(fixture.jdbc, "sa", "")) {
            assertTrue(tableExists(destination, "dst_backups"));
        }
    }

    @Test
    void crashAfterCommitBeforeCheckpointReplaysIdenticalRowOnResume() throws Exception {
        Fixture fixture = fixture("commit_crash", "src_", "dst_");
        AtomicBoolean first = new AtomicBoolean(true);
        SqliteToMysqlMigration crashing = fixture.withHook(point -> {
            if (point == SqliteToMysqlMigration.FailurePoint.AFTER_DESTINATION_COMMIT_BEFORE_CHECKPOINT
                    && first.getAndSet(false)) throw new ExpectedCrash();
        });
        String fingerprint = crashing.dryRun().fingerprint();

        assertThrows(ExpectedCrash.class,
                () -> crashing.run(SqliteToMysqlMigration.Mode.START, fingerprint));
        assertEquals(SqliteToMysqlMigration.Status.RUNNING, crashing.status().status());

        SqliteToMysqlMigration.RunResult resumed = fixture.migration.run(
                SqliteToMysqlMigration.Mode.RESUME, fingerprint);
        assertEquals(SqliteToMysqlMigration.Status.COMPLETED, resumed.status());
        assertTrue(resumed.skipped() >= 1);
        try (Connection destination = DriverManager.getConnection(fixture.jdbc, "sa", "")) {
            assertEquals(2, scalar(destination, "SELECT COUNT(*) FROM dst_pages"));
        }
    }

    @Test
    void crashAfterCheckpointSkipsConfirmedKeyOnResume() throws Exception {
        Fixture fixture = fixture("checkpoint_crash", "src_", "dst_");
        AtomicBoolean first = new AtomicBoolean(true);
        SqliteToMysqlMigration crashing = fixture.withHook(point -> {
            if (point == SqliteToMysqlMigration.FailurePoint.AFTER_CHECKPOINT
                    && first.getAndSet(false)) throw new ExpectedCrash();
        });
        String fingerprint = crashing.dryRun().fingerprint();

        assertThrows(ExpectedCrash.class,
                () -> crashing.run(SqliteToMysqlMigration.Mode.START, fingerprint));
        assertNotNull(crashing.status().lastKey());

        SqliteToMysqlMigration.RunResult resumed = fixture.migration.run(
                SqliteToMysqlMigration.Mode.RESUME, fingerprint);
        assertEquals(SqliteToMysqlMigration.Status.COMPLETED, resumed.status());
        assertEquals(0, resumed.skipped());
    }

    @Test
    void changedSourceFingerprintAndDifferentDestinationFailClosed() throws Exception {
        Fixture changed = fixture("changed", "src_", "dst_");
        AtomicBoolean first = new AtomicBoolean(true);
        SqliteToMysqlMigration crashing = changed.withHook(point -> {
            if (point == SqliteToMysqlMigration.FailurePoint.AFTER_CHECKPOINT
                    && first.getAndSet(false)) throw new ExpectedCrash();
        });
        String oldFingerprint = crashing.dryRun().fingerprint();
        assertThrows(ExpectedCrash.class,
                () -> crashing.run(SqliteToMysqlMigration.Mode.START, oldFingerprint));
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + changed.source)) {
            source.createStatement().executeUpdate("UPDATE src_extra SET extra=9");
        }
        String newFingerprint = changed.migration.dryRun().fingerprint();
        SqliteToMysqlMigration.RunResult mismatch = changed.migration.run(
                SqliteToMysqlMigration.Mode.RESUME, newFingerprint);
        assertEquals(SqliteToMysqlMigration.Status.CONFLICT, mismatch.status());

        Fixture conflict = fixture("conflict", "src_", "dst_");
        String fingerprint = conflict.migration.dryRun().fingerprint();
        createDestinationSchema(conflict.jdbc, "dst_");
        try (Connection destination = DriverManager.getConnection(conflict.jdbc, "sa", "")) {
            destination.createStatement().executeUpdate(
                    "INSERT INTO dst_pages(uuid,page,data,revision) VALUES ('"
                            + OWNER_A + "',1,'different',7)");
        }
        assertEquals(1, conflict.migration.dryRun().conflicts());
        SqliteToMysqlMigration.RunResult result = conflict.migration.run(
                SqliteToMysqlMigration.Mode.START, fingerprint);
        assertEquals(SqliteToMysqlMigration.Status.CONFLICT, result.status());
        assertEquals(SqliteToMysqlMigration.Status.CONFLICT, conflict.migration.status().status());
        SqliteToMysqlMigration.RunResult retry = conflict.migration.run(
                SqliteToMysqlMigration.Mode.RESUME, fingerprint);
        assertEquals(SqliteToMysqlMigration.Status.CONFLICT, retry.status());
    }

    @Test
    void unavailableDestinationDoesNotCreateCheckpoint() throws Exception {
        Path source = temp.resolve("unavailable.db");
        createSource(source, "src_");
        Path checkpoint = temp.resolve("unavailable.json");
        SqliteToMysqlMigration migration = new SqliteToMysqlMigration(source, "src_",
                new SqliteToMysqlMigration.Destination(
                        "jdbc:mysql://127.0.0.1:1/none?connectTimeout=100", "user", "secret",
                        "127.0.0.1:1/none", "dst_", true), checkpoint);

        assertThrows(Exception.class, migration::dryRun);
        assertFalse(Files.exists(checkpoint));
        assertFalse(migration.toString().contains("secret"));
        assertFalse(migration.status().conflict() != null
                && migration.status().conflict().contains("secret"));
    }

    private Fixture fixture(String name, String sourcePrefix, String destinationPrefix) throws Exception {
        Path source = temp.resolve(name + ".db");
        createSource(source, sourcePrefix);
        String jdbc = "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Path checkpoint = temp.resolve(name + ".json");
        SqliteToMysqlMigration.Destination destination = new SqliteToMysqlMigration.Destination(
                jdbc, "sa", "", "h2/" + name, destinationPrefix, false);
        return new Fixture(source, sourcePrefix, jdbc, checkpoint, destination,
                new SqliteToMysqlMigration(source, sourcePrefix, destination, checkpoint));
    }

    private static void createSource(Path source, String prefix) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + prefix + "pages (uuid VARCHAR(36) NOT NULL, page TINYINT NOT NULL, data TEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(uuid,page))");
            statement.execute("CREATE TABLE " + prefix + "extra (uuid VARCHAR(36) PRIMARY KEY, extra INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE " + prefix + "migrated (uuid VARCHAR(36) NOT NULL, type VARCHAR(32) NOT NULL, PRIMARY KEY(uuid,type))");
            statement.execute("INSERT INTO " + prefix + "pages VALUES ('" + OWNER_A + "',1,'[null]',7)");
            statement.execute("INSERT INTO " + prefix + "pages VALUES ('" + OWNER_B + "',2,'[null,null]',9)");
            statement.execute("INSERT INTO " + prefix + "extra VALUES ('" + OWNER_A + "',1)");
            statement.execute("INSERT INTO " + prefix + "migrated VALUES ('" + OWNER_B + "','vanilla')");
        }
    }

    private static void createDestinationSchema(String jdbc, String prefix) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbc, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + prefix + "pages (uuid VARCHAR(36), page TINYINT, data MEDIUMTEXT, revision BIGINT, PRIMARY KEY(uuid,page))");
            statement.execute("CREATE TABLE " + prefix + "extra (uuid VARCHAR(36) PRIMARY KEY, extra INT)");
            statement.execute("CREATE TABLE " + prefix + "migrated (uuid VARCHAR(36), type VARCHAR(32), PRIMARY KEY(uuid,type))");
            statement.execute("CREATE TABLE " + prefix + "backups (id INT AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36), page TINYINT, revision BIGINT, reason VARCHAR(32), created_at BIGINT, data MEDIUMTEXT)");
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var tables = connection.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return tables.next();
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (var result = connection.createStatement().executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private record Fixture(Path source, String sourcePrefix, String jdbc, Path checkpoint,
                           SqliteToMysqlMigration.Destination destination,
                           SqliteToMysqlMigration migration) {
        private SqliteToMysqlMigration withHook(SqliteToMysqlMigration.FailureHook hook) {
            return new SqliteToMysqlMigration(source, sourcePrefix, destination, checkpoint, hook);
        }
    }

    private static final class ExpectedCrash extends Exception {}
    private static final String OWNER_A = UUID.fromString("11111111-1111-1111-1111-111111111111").toString();
    private static final String OWNER_B = UUID.fromString("22222222-2222-2222-2222-222222222222").toString();
}
