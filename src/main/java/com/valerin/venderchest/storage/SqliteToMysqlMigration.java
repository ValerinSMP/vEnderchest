package com.valerin.venderchest.storage;

import com.google.gson.Gson;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only SQLite to separate MySQL copy. It never mutates, renames, or deletes the source. */
public final class SqliteToMysqlMigration {

    private static final int CHECKPOINT_VERSION = 1;
    private static final Gson GSON = new Gson();
    private final Path source;
    private final String sourcePrefix;
    private final Destination destination;
    private final Path checkpointFile;
    private final FailureHook failureHook;

    public SqliteToMysqlMigration(
            Path source, String sourcePrefix, Destination destination, Path checkpointFile) {
        this(source, sourcePrefix, destination, checkpointFile, point -> {});
    }

    SqliteToMysqlMigration(
            Path source, String sourcePrefix, Destination destination, Path checkpointFile,
            FailureHook failureHook) {
        if (!sourcePrefix.matches("[A-Za-z0-9_]+") || !destination.prefix().matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("invalid migration table prefix");
        }
        this.source = source.toAbsolutePath().normalize();
        this.sourcePrefix = sourcePrefix;
        this.destination = destination;
        this.checkpointFile = checkpointFile.toAbsolutePath().normalize();
        this.failureHook = failureHook;
    }

    /** Informational snapshot only. No DDL, DML, checkpoint, or source write. */
    public Inspection dryRun() throws Exception {
        try (Connection sqlite = openSource(); Connection mysql = destination.connect()) {
            validateSource(sqlite);
            DestinationSchema schema = destinationSchema(mysql);
            SourceSnapshot snapshot = sourceSnapshot(sqlite);
            Comparison comparison = compareRows(sqlite, mysql, schema);
            return new Inspection(source, snapshot.fingerprint, snapshot.owners, snapshot.pages,
                    snapshot.bytes, comparison.missing, comparison.equal, comparison.conflictCount,
                    comparison.conflictKeys, schema.any());
        }
    }

    /** START requires no checkpoint; RESUME requires one with an exact identity/fingerprint match. */
    public RunResult run(Mode mode, String preflightFingerprint) throws Exception {
        try (Connection sqlite = openSource(); Connection mysql = destination.connect()) {
            validateSource(sqlite);
            SourceSnapshot snapshot = sourceSnapshot(sqlite);
            if (!snapshot.fingerprint.equals(preflightFingerprint)) {
                return RunResult.failed(Status.CONFLICT, "source changed after preflight");
            }
            Checkpoint checkpoint = loadCheckpoint();
            if (mode == Mode.START && checkpoint != null) {
                return RunResult.failed(Status.CONFLICT, "checkpoint already exists; use resume");
            }
            if (mode == Mode.RESUME) {
                String mismatch = checkpointMismatch(checkpoint, snapshot.fingerprint);
                if (mismatch != null) return RunResult.failed(Status.CONFLICT, mismatch);
            }

            try (NamedLock ignored = acquireProcessLock()) {
                createHistoricalSchema(mysql);
                validateDestination(mysql);

                checkpoint = checkpoint == null
                        ? new Checkpoint(CHECKPOINT_VERSION, UUID.randomUUID().toString(),
                                Status.RUNNING, source.toString(), snapshot.fingerprint,
                                destination.identity(), destination.prefix(), null, 0, 0, 0,
                                snapshot.pages, snapshot.bytes, snapshot.hash, null,
                                System.currentTimeMillis())
                        : checkpoint;
                Progress progress = new Progress(checkpoint);
                if (mode == Mode.START) writeCheckpoint(checkpoint);

                try {
                    copyPages(sqlite, mysql, progress, checkpoint);
                    copyExtras(sqlite, mysql, progress, checkpoint);
                    copyFlags(sqlite, mysql, progress, checkpoint);
                    Verification verified = verify(sqlite, mysql);
                    if (verified.pages != snapshot.pages || !verified.hash.equals(snapshot.hash)) {
                        throw new RowConflict("final verification hash/count mismatch", "VERIFY");
                    }
                    Checkpoint complete = checkpoint.with(Status.COMPLETED, progress.lastKey,
                            progress.inserted, progress.skipped, progress.processed,
                            null, verified.hash);
                    writeCheckpoint(complete);
                    return new RunResult(Status.COMPLETED, "migration completed", progress.inserted,
                            progress.skipped, progress.processed, verified.hash);
                } catch (RowConflict conflict) {
                    Checkpoint failed = checkpoint.with(Status.CONFLICT, progress.lastKey,
                            progress.inserted, progress.skipped, progress.processed,
                            conflict.key + ": " + conflict.getMessage(), null);
                    writeCheckpoint(failed);
                    return new RunResult(Status.CONFLICT, failed.conflict, progress.inserted,
                            progress.skipped, progress.processed, null);
                }
            }
        }
    }

    /** Local COW file only: status never opens SQLite or MySQL. */
    public StatusReport status() {
        try {
            Checkpoint checkpoint = loadCheckpoint();
            if (checkpoint == null) return new StatusReport(Status.NOT_STARTED, null, null, 0, 0, 0, null);
            return new StatusReport(checkpoint.status, checkpoint.runId, checkpoint.lastKey,
                    checkpoint.inserted, checkpoint.skipped, checkpoint.processed, checkpoint.conflict);
        } catch (Exception error) {
            return new StatusReport(Status.CONFLICT, null, null, 0, 0, 0,
                    "checkpoint cannot be read");
        }
    }

    private void copyPages(Connection sqlite, Connection mysql,
                           Progress progress, Checkpoint base) throws Exception {
        String sql = "SELECT uuid, page, data, revision FROM " + sourceTable("pages") + " ORDER BY uuid, page";
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(sql)) {
            while (rows.next()) {
                String owner = rows.getString(1);
                int page = rows.getInt(2);
                String data = rows.getString(3);
                long revision = rows.getLong(4);
                String key = "1P|" + owner + "|" + String.format("%05d", page);
                if (progress.alreadyCheckpointed(key)) continue;
                mysql.setAutoCommit(false);
                try {
                    RowState state = pageState(mysql, owner, page, data, revision);
                    if (state == RowState.CONFLICT) throw new RowConflict("destination page differs", key);
                    if (state == RowState.MISSING) {
                        try (PreparedStatement insert = mysql.prepareStatement(
                                "INSERT INTO " + table("pages")
                                        + " (uuid, page, data, revision) VALUES (?, ?, ?, ?)")) {
                            insert.setString(1, owner);
                            insert.setInt(2, page);
                            insert.setString(3, data);
                            insert.setLong(4, revision);
                            insert.executeUpdate();
                        }
                        progress.inserted++;
                    } else {
                        progress.skipped++;
                    }
                    mysql.commit();
                    failureHook.at(FailurePoint.AFTER_DESTINATION_COMMIT_BEFORE_CHECKPOINT);
                } catch (Exception error) {
                    mysql.rollback();
                    if (error instanceof RowConflict conflict) throw conflict;
                    throw error;
                } finally {
                    mysql.setAutoCommit(true);
                }
                progress.record(key);
                writeCheckpoint(base.with(Status.RUNNING, key, progress.inserted,
                        progress.skipped, progress.processed, null, null));
                failureHook.at(FailurePoint.AFTER_CHECKPOINT);
            }
        }
    }

    private void copyExtras(Connection sqlite, Connection mysql,
                            Progress progress, Checkpoint base) throws Exception {
        try (var read = sqlite.createStatement();
             ResultSet rows = read.executeQuery("SELECT uuid, extra FROM " + sourceTable("extra") + " ORDER BY uuid")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                int extra = rows.getInt(2);
                String key = "2E|" + owner;
                if (progress.alreadyCheckpointed(key)) continue;
                mysql.setAutoCommit(false);
                try {
                    Integer existing = integerValue(mysql,
                            "SELECT extra FROM " + table("extra") + " WHERE uuid = ?", owner);
                    if (existing != null && existing != extra) throw new RowConflict("destination extra differs", key);
                    if (existing == null) {
                        try (PreparedStatement insert = mysql.prepareStatement(
                                "INSERT INTO " + table("extra") + " (uuid, extra) VALUES (?, ?)")) {
                            insert.setString(1, owner);
                            insert.setInt(2, extra);
                            insert.executeUpdate();
                        }
                        progress.inserted++;
                    } else progress.skipped++;
                    mysql.commit();
                    failureHook.at(FailurePoint.AFTER_DESTINATION_COMMIT_BEFORE_CHECKPOINT);
                } catch (Exception error) {
                    mysql.rollback();
                    if (error instanceof RowConflict conflict) throw conflict;
                    throw error;
                } finally { mysql.setAutoCommit(true); }
                progress.record(key);
                writeCheckpoint(base.with(Status.RUNNING, key, progress.inserted,
                        progress.skipped, progress.processed, null, null));
                failureHook.at(FailurePoint.AFTER_CHECKPOINT);
            }
        }
    }

    private void copyFlags(Connection sqlite, Connection mysql,
                           Progress progress, Checkpoint base) throws Exception {
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, type FROM " + sourceTable("migrated") + " ORDER BY uuid, type")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                String type = rows.getString(2);
                String key = "3M|" + owner + "|" + type;
                if (progress.alreadyCheckpointed(key)) continue;
                mysql.setAutoCommit(false);
                try {
                    boolean exists;
                    try (PreparedStatement select = mysql.prepareStatement(
                            "SELECT 1 FROM " + table("migrated") + " WHERE uuid = ? AND type = ?")) {
                        select.setString(1, owner);
                        select.setString(2, type);
                        try (ResultSet found = select.executeQuery()) { exists = found.next(); }
                    }
                    if (!exists) {
                        try (PreparedStatement insert = mysql.prepareStatement(
                                "INSERT INTO " + table("migrated") + " (uuid, type) VALUES (?, ?)")) {
                            insert.setString(1, owner);
                            insert.setString(2, type);
                            insert.executeUpdate();
                        }
                        progress.inserted++;
                    } else progress.skipped++;
                    mysql.commit();
                    failureHook.at(FailurePoint.AFTER_DESTINATION_COMMIT_BEFORE_CHECKPOINT);
                } catch (Exception error) {
                    mysql.rollback();
                    throw error;
                } finally { mysql.setAutoCommit(true); }
                progress.record(key);
                writeCheckpoint(base.with(Status.RUNNING, key, progress.inserted,
                        progress.skipped, progress.processed, null, null));
                failureHook.at(FailurePoint.AFTER_CHECKPOINT);
            }
        }
    }

    private SourceSnapshot sourceSnapshot(Connection sqlite) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Set<UUID> owners = new HashSet<>();
        long pages = 0;
        long bytes = 0;
        hashSchema(sqlite, digest);
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, page, data, revision FROM " + sourceTable("pages") + " ORDER BY uuid, page")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                String data = rows.getString(3);
                owners.add(UUID.fromString(owner));
                pages++;
                bytes += data.getBytes(StandardCharsets.UTF_8).length;
                updateHash(digest, "P", owner, Integer.toString(rows.getInt(2)),
                        Long.toString(rows.getLong(4)), data);
            }
        }
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, extra FROM " + sourceTable("extra") + " ORDER BY uuid")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                owners.add(UUID.fromString(owner));
                updateHash(digest, "E", owner, Integer.toString(rows.getInt(2)));
            }
        }
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, type FROM " + sourceTable("migrated") + " ORDER BY uuid, type")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                owners.add(UUID.fromString(owner));
                updateHash(digest, "M", owner, rows.getString(2));
            }
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        MessageDigest fingerprint = MessageDigest.getInstance("SHA-256");
        updateHash(fingerprint, source.toString(), sourcePrefix, hash, Long.toString(pages), Long.toString(bytes));
        return new SourceSnapshot(Set.copyOf(owners), pages, bytes, hash,
                HexFormat.of().formatHex(fingerprint.digest()));
    }

    private Verification verify(Connection sqlite, Connection mysql) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long pages = 0;
        hashSchema(sqlite, digest);
        try (var read = mysql.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, page, data, revision FROM " + table("pages") + " ORDER BY uuid, page")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                int page = rows.getInt(2);
                updateHash(digest, "P", owner, Integer.toString(page),
                        Long.toString(rows.getLong(4)), rows.getString(3));
                pages++;
            }
        }
        try (var read = mysql.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, extra FROM " + table("extra") + " ORDER BY uuid")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                updateHash(digest, "E", owner, Integer.toString(rows.getInt(2)));
            }
        }
        try (var read = mysql.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, type FROM " + table("migrated") + " ORDER BY uuid, type")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                String type = rows.getString(2);
                updateHash(digest, "M", owner, type);
            }
        }
        return new Verification(pages, HexFormat.of().formatHex(digest.digest()));
    }

    private Comparison compareRows(
            Connection sqlite, Connection mysql, DestinationSchema schema) throws SQLException {
        long missing = 0;
        long equal = 0;
        long conflictCount = 0;
        List<String> conflicts = new ArrayList<>();
        Set<String> sourcePages = new HashSet<>();
        Set<String> sourceExtras = new HashSet<>();
        Set<String> sourceFlags = new HashSet<>();
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, page, data, revision FROM " + sourceTable("pages") + " ORDER BY uuid, page")) {
            while (rows.next()) {
                sourcePages.add(rows.getString(1) + "|" + rows.getInt(2));
                RowState state = schema.pages
                        ? pageState(mysql, rows.getString(1), rows.getInt(2),
                                rows.getString(3), rows.getLong(4))
                        : RowState.MISSING;
                if (state == RowState.MISSING) missing++;
                else if (state == RowState.EQUAL) equal++;
                else {
                    conflictCount++;
                    if (conflicts.size() < 20) conflicts.add(rows.getString(1) + "/" + rows.getInt(2));
                }
            }
        }
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, extra FROM " + sourceTable("extra") + " ORDER BY uuid")) {
            while (rows.next()) {
                String owner = rows.getString(1);
                sourceExtras.add(owner);
                Integer target = schema.extra ? integerValue(mysql,
                        "SELECT extra FROM " + table("extra") + " WHERE uuid = ?", owner) : null;
                if (target == null) missing++;
                else if (target == rows.getInt(2)) equal++;
                else {
                    conflictCount++;
                    if (conflicts.size() < 20) conflicts.add("extra/" + owner);
                }
            }
        }
        try (var read = sqlite.createStatement(); ResultSet rows = read.executeQuery(
                "SELECT uuid, type FROM " + sourceTable("migrated") + " ORDER BY uuid, type")) {
            while (rows.next()) {
                sourceFlags.add(rows.getString(1) + "|" + rows.getString(2));
                if (!schema.migrated) {
                    missing++;
                } else {
                    try (PreparedStatement select = mysql.prepareStatement(
                            "SELECT 1 FROM " + table("migrated") + " WHERE uuid = ? AND type = ?")) {
                        select.setString(1, rows.getString(1));
                        select.setString(2, rows.getString(2));
                        try (ResultSet target = select.executeQuery()) {
                            if (target.next()) equal++; else missing++;
                        }
                    }
                }
            }
        }
        if (schema.pages) {
            try (var read = mysql.createStatement(); ResultSet rows = read.executeQuery(
                    "SELECT uuid, page FROM " + table("pages") + " ORDER BY uuid, page")) {
                while (rows.next()) {
                    String key = rows.getString(1) + "|" + rows.getInt(2);
                    if (!sourcePages.contains(key)) {
                        conflictCount++;
                        if (conflicts.size() < 20) conflicts.add("destination-only/" + key);
                    }
                }
            }
        }
        if (schema.extra) {
            try (var read = mysql.createStatement(); ResultSet rows = read.executeQuery(
                    "SELECT uuid FROM " + table("extra") + " ORDER BY uuid")) {
                while (rows.next()) {
                    String key = rows.getString(1);
                    if (!sourceExtras.contains(key)) {
                        conflictCount++;
                        if (conflicts.size() < 20) conflicts.add("destination-only/extra/" + key);
                    }
                }
            }
        }
        if (schema.migrated) {
            try (var read = mysql.createStatement(); ResultSet rows = read.executeQuery(
                    "SELECT uuid, type FROM " + table("migrated") + " ORDER BY uuid, type")) {
                while (rows.next()) {
                    String key = rows.getString(1) + "|" + rows.getString(2);
                    if (!sourceFlags.contains(key)) {
                        conflictCount++;
                        if (conflicts.size() < 20) conflicts.add("destination-only/migrated/" + key);
                    }
                }
            }
        }
        return new Comparison(missing, equal, conflictCount, List.copyOf(conflicts));
    }

    private void hashSchema(Connection sqlite, MessageDigest digest) throws SQLException {
        for (String suffix : List.of("pages", "extra", "migrated")) {
            try (var read = sqlite.createStatement(); ResultSet columns = read.executeQuery(
                    "PRAGMA table_info(" + sourceTable(suffix) + ")")) {
                while (columns.next()) {
                    updateHash(digest, "S", suffix, Integer.toString(columns.getInt("cid")),
                            columns.getString("name"), columns.getString("type"),
                            Integer.toString(columns.getInt("notnull")),
                            Integer.toString(columns.getInt("pk")));
                }
            }
        }
    }

    private void createHistoricalSchema(Connection mysql) throws SQLException {
        String options = destination.mysql()
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
        try (var statement = mysql.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("pages")
                    + " (uuid VARCHAR(36) NOT NULL, page TINYINT NOT NULL, data MEDIUMTEXT NOT NULL,"
                    + " revision BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, page))" + options);
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("extra")
                    + " (uuid VARCHAR(36) PRIMARY KEY, extra INT NOT NULL DEFAULT 0)" + options);
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("migrated")
                    + " (uuid VARCHAR(36) NOT NULL, type VARCHAR(32) NOT NULL, PRIMARY KEY (uuid, type))" + options);
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("backups")
                    + " (id INT NOT NULL AUTO_INCREMENT, uuid VARCHAR(36) NOT NULL, page TINYINT NOT NULL,"
                    + " revision BIGINT NOT NULL, reason VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL,"
                    + " data MEDIUMTEXT NOT NULL, PRIMARY KEY (id))" + options);
        }
    }

    private DestinationSchema destinationSchema(Connection mysql) throws SQLException {
        boolean pages = tableExists(mysql, table("pages"));
        boolean extra = tableExists(mysql, table("extra"));
        boolean migrated = tableExists(mysql, table("migrated"));
        boolean backups = tableExists(mysql, table("backups"));
        if (pages) requireColumns(mysql, table("pages"), Set.of("uuid", "page", "data", "revision"));
        if (extra) requireColumns(mysql, table("extra"), Set.of("uuid", "extra"));
        if (migrated) requireColumns(mysql, table("migrated"), Set.of("uuid", "type"));
        if (backups) requireColumns(mysql, table("backups"), Set.of(
                "id", "uuid", "page", "revision", "reason", "created_at", "data"));
        return new DestinationSchema(pages, extra, migrated, backups);
    }

    private void validateSource(Connection c) throws SQLException {
        requireColumns(c, sourceTable("pages"), Set.of("uuid", "page", "data", "revision"));
        requireColumns(c, sourceTable("extra"), Set.of("uuid", "extra"));
        requireColumns(c, sourceTable("migrated"), Set.of("uuid", "type"));
    }

    private void validateDestination(Connection c) throws SQLException {
        requireColumns(c, table("pages"), Set.of("uuid", "page", "data", "revision"));
        requireColumns(c, table("extra"), Set.of("uuid", "extra"));
        requireColumns(c, table("migrated"), Set.of("uuid", "type"));
        requireColumns(c, table("backups"), Set.of(
                "id", "uuid", "page", "revision", "reason", "created_at", "data"));
    }

    private RowState pageState(Connection c, String owner, int page, String data, long revision)
            throws SQLException {
        try (PreparedStatement select = c.prepareStatement(
                "SELECT data, revision FROM " + table("pages") + " WHERE uuid = ? AND page = ?")) {
            select.setString(1, owner);
            select.setInt(2, page);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) return RowState.MISSING;
                return row.getLong(2) == revision && data.equals(row.getString(1))
                        ? RowState.EQUAL : RowState.CONFLICT;
            }
        }
    }

    private Integer integerValue(Connection c, String sql, String owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    private NamedLock acquireProcessLock() throws Exception {
        if (!destination.mysql()) return NamedLock.none();
        Connection connection = destination.connect();
        String name = "vecm:" + sha256(destination.identity() + "|" + destination.prefix()).substring(0, 59);
        try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, 3)")) {
            lock.setString(1, name);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    connection.close();
                    throw new SQLException("another migration process owns the destination");
                }
            }
        } catch (Exception error) {
            connection.close();
            throw error;
        }
        return new NamedLock(connection, name);
    }

    private Connection openSource() throws Exception {
        if (!Files.isRegularFile(source)) throw new SQLException("SQLite source does not exist");
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.READONLY);
        config.setReadOnly(true);
        String uri = source.toUri().toASCIIString();
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + uri + (uri.contains("?") ? "&" : "?") + "mode=ro",
                config.toProperties());
        connection.setReadOnly(true);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    private Checkpoint loadCheckpoint() throws Exception {
        if (!Files.exists(checkpointFile)) return null;
        return GSON.fromJson(Files.readString(checkpointFile, StandardCharsets.UTF_8), Checkpoint.class);
    }

    private void writeCheckpoint(Checkpoint checkpoint) throws Exception {
        Files.createDirectories(checkpointFile.getParent());
        Path temporary = checkpointFile.resolveSibling(checkpointFile.getFileName() + ".tmp");
        byte[] encoded = GSON.toJson(checkpoint).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(encoded));
            channel.force(true);
        }
        try {
            Files.move(temporary, checkpointFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, checkpointFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String checkpointMismatch(Checkpoint checkpoint, String fingerprint) {
        if (checkpoint == null) return "checkpoint is missing; use start";
        if (checkpoint.version != CHECKPOINT_VERSION) return "checkpoint schema version differs";
        if (!source.toString().equals(checkpoint.sourcePath)) return "source path differs from checkpoint";
        if (!fingerprint.equals(checkpoint.sourceFingerprint)) return "source fingerprint differs from checkpoint";
        if (!destination.identity().equals(checkpoint.destinationIdentity)
                || !destination.prefix().equals(checkpoint.destinationPrefix)) {
            return "destination identity or prefix differs from checkpoint";
        }
        if (checkpoint.status == Status.COMPLETED) return "migration is already completed";
        if (checkpoint.status == Status.CONFLICT) return "migration conflict is terminal; inspect status";
        return null;
    }

    private void requireColumns(Connection c, String table, Set<String> required) throws SQLException {
        DatabaseMetaData metadata = c.getMetaData();
        Set<String> actual = new HashSet<>();
        for (String name : List.of(table, table.toUpperCase())) {
            try (ResultSet columns = metadata.getColumns(c.getCatalog(), null, name, null)) {
                while (columns.next()) actual.add(columns.getString("COLUMN_NAME").toLowerCase());
            }
            if (!actual.isEmpty()) break;
        }
        if (!actual.containsAll(required)) {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(actual);
            throw new SQLException("table " + table + " missing columns " + missing);
        }
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : List.of(table, table.toUpperCase())) {
            try (ResultSet tables = c.getMetaData().getTables(c.getCatalog(), null, name, new String[]{"TABLE"})) {
                if (tables.next()) return true;
            }
        }
        return false;
    }

    private void updateHash(MessageDigest digest, String... parts) {
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String sourceTable(String suffix) { return sourcePrefix + suffix; }
    private String table(String suffix) { return destination.prefix() + suffix; }

    public enum Mode { START, RESUME }
    public enum Status { NOT_STARTED, RUNNING, CONFLICT, COMPLETED }
    private enum RowState { MISSING, EQUAL, CONFLICT }

    public record Destination(String jdbcUrl, String username, String password,
                              String identity, String prefix, boolean mysql) {
        public Destination {
            if (jdbcUrl == null || username == null || password == null || identity == null || prefix == null) {
                throw new IllegalArgumentException("destination is incomplete");
            }
        }
        public static Destination mysql(String host, int port, String database,
                                        String username, String password, String prefix) {
            String identity = host + ":" + port + "/" + database;
            String url = "jdbc:mysql://" + identity
                    + "?useSSL=false&connectTimeout=3000&socketTimeout=10000";
            return new Destination(url, username, password == null ? "" : password, identity, prefix, true);
        }
        Connection connect() throws SQLException { return DriverManager.getConnection(jdbcUrl, username, password); }
        @Override public String toString() {
            return "Destination[identity=" + identity + ", prefix=" + prefix + ", credentials=<redacted>]";
        }
    }

    public record Inspection(Path source, String fingerprint, Set<UUID> owners,
                             long pages, long bytes, long missing, long equal, long conflicts,
                             List<String> conflictKeys, boolean destinationSchemaPresent) {
        public Inspection { owners = Set.copyOf(owners); conflictKeys = List.copyOf(conflictKeys); }
    }

    public record RunResult(Status status, String message, long inserted,
                            long skipped, long processed, String verifiedHash) {
        private static RunResult failed(Status status, String message) {
            return new RunResult(status, message, 0, 0, 0, null);
        }
    }

    public record StatusReport(Status status, String runId, String lastKey,
                               long inserted, long skipped, long processed, String conflict) {}

    private record SourceSnapshot(Set<UUID> owners, long pages, long bytes,
                                  String hash, String fingerprint) {}
    private record Verification(long pages, String hash) {}
    private record Comparison(long missing, long equal, long conflictCount, List<String> conflictKeys) {}
    private record DestinationSchema(boolean pages, boolean extra, boolean migrated, boolean backups) {
        private boolean any() { return pages || extra || migrated || backups; }
    }

    enum FailurePoint { AFTER_DESTINATION_COMMIT_BEFORE_CHECKPOINT, AFTER_CHECKPOINT }

    @FunctionalInterface
    interface FailureHook {
        void at(FailurePoint point) throws Exception;
    }

    private static final class Progress {
        private long inserted;
        private long skipped;
        private long processed;
        private String lastKey;
        private Progress(Checkpoint checkpoint) {
            inserted = checkpoint.inserted;
            skipped = checkpoint.skipped;
            processed = checkpoint.processed;
            lastKey = checkpoint.lastKey;
        }
        private boolean alreadyCheckpointed(String key) {
            return lastKey != null && key.compareTo(lastKey) <= 0;
        }
        private void record(String key) { lastKey = key; processed++; }
    }

    private static final class RowConflict extends Exception {
        private final String key;
        private RowConflict(String message, String key) { super(message); this.key = key; }
    }

    private record Checkpoint(int version, String runId, Status status, String sourcePath,
                              String sourceFingerprint, String destinationIdentity,
                              String destinationPrefix, String lastKey, long inserted,
                              long skipped, long processed, long sourcePages, long sourceBytes,
                              String sourceHash, String conflict, long updatedAt) {
        private Checkpoint with(Status next, String key, long inserted, long skipped, long processed,
                                String conflict, String verifiedHash) {
            return new Checkpoint(version, runId, next, sourcePath, sourceFingerprint,
                    destinationIdentity, destinationPrefix, key, inserted, skipped, processed,
                    sourcePages, sourceBytes, verifiedHash == null ? sourceHash : verifiedHash,
                    conflict, System.currentTimeMillis());
        }
    }

    private static final class NamedLock implements AutoCloseable {
        private final Connection connection;
        private final String name;

        private NamedLock(Connection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        private static NamedLock none() { return new NamedLock(null, null); }

        @Override
        public void close() throws SQLException {
            if (connection == null) return;
            try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                release.setString(1, name);
                release.executeQuery();
            } finally {
                connection.close();
            }
        }
    }
}
