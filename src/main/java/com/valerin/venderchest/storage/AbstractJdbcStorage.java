package com.valerin.venderchest.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

abstract class AbstractJdbcStorage implements Storage {

    protected HikariDataSource dataSource;
    private static final Gson GSON = new Gson();
    private static final int PAGE_SIZE = 45;
    private final String tablePrefix;

    protected AbstractJdbcStorage() {
        this("ec_");
    }

    protected AbstractJdbcStorage(String tablePrefix) {
        if (tablePrefix == null || !tablePrefix.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid database table prefix");
        }
        this.tablePrefix = tablePrefix;
    }

    protected final String table(String suffix) {
        return tablePrefix + suffix;
    }

    @Override
    public void init() throws SQLException {
        dataSource = createDataSource();
        try (Connection c = dataSource.getConnection();
             var stmt = c.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) NOT NULL,
                    page TINYINT NOT NULL,
                    data TEXT NOT NULL,
                    PRIMARY KEY (uuid, page)
                )
                """.formatted(table("pages")));
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) PRIMARY KEY,
                    extra INTEGER NOT NULL DEFAULT 0
                )
                """.formatted(table("extra")));
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    PRIMARY KEY (uuid, type)
                )
                """.formatted(table("migrated")));
            stmt.execute(backupsTableSql());
            migrateRevisionColumn(c);
        }
    }

    /** Dialect-specific: SQLite and MySQL spell auto-increment differently. */
    protected String backupsTableSql() {
        return """
            CREATE TABLE IF NOT EXISTS %s (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid VARCHAR(36) NOT NULL,
                page TINYINT NOT NULL,
                revision BIGINT NOT NULL,
                reason VARCHAR(32) NOT NULL,
                created_at BIGINT NOT NULL,
                data TEXT NOT NULL
            )
            """.formatted(table("backups"));
    }

    /**
     * Additive, idempotent migration: adds the optimistic-concurrency revision column to
     * pre-existing installs. Safe to run on every startup — a SQLException here only ever means
     * the column already exists (both SQLite and MySQL lack "ADD COLUMN IF NOT EXISTS"), so it is
     * swallowed. Never touches existing rows' data.
     */
    private void migrateRevisionColumn(Connection c) {
        try (var stmt = c.createStatement()) {
            stmt.execute("ALTER TABLE " + table("pages") + " ADD COLUMN revision BIGINT NOT NULL DEFAULT 0");
        } catch (SQLException alreadyExists) {
            // Column already present from a previous startup — expected on every run after the first.
        }
    }

    protected abstract HikariDataSource createDataSource();

    @Override
    public ItemStack[] loadPage(UUID uuid, int page) {
        return loadPageWithRevision(uuid, page).items();
    }

    @Override
    public PageRecord loadPageWithRevision(UUID uuid, int page) {
        String sql = "SELECT data, revision FROM " + table("pages") + " WHERE uuid = ? AND page = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PageRecord(deserialize(rs.getString("data")), rs.getLong("revision"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new PageRecord(new ItemStack[PAGE_SIZE], 0L);
    }

    /** Dialect-specific "create row only if absent" statement, revision seeded at 1. */
    protected String insertIfAbsentSql() {
        return "INSERT OR IGNORE INTO " + table("pages")
                + " (uuid, page, data, revision) VALUES (?, ?, ?, 1)";
    }

    protected String insertFenceIfAbsentSql() {
        return "INSERT OR IGNORE INTO " + table("player_fence") + " (uuid, fence) VALUES (?, 0)";
    }

    @Override
    public SaveResult savePageIfRevision(UUID uuid, int page, ItemStack[] items, long expectedRevision) {
        String json = serialize(items);
        try (Connection c = dataSource.getConnection()) {
            return savePageIfRevision(c, uuid, page, json, expectedRevision);
        } catch (SQLException e) {
            e.printStackTrace();
            return new SaveResult.Failure(e.getMessage());
        }
    }

    SaveResult savePageIfRevision(
            Connection c, UUID uuid, int page, String json, long expectedRevision) throws SQLException {
            if (expectedRevision == 0) {
                /*
                 * Revision 0 is ambiguous after upgrading from the old schema: existing rows
                 * receive DEFAULT 0 when the revision column is added. It can also represent a
                 * page that has never been saved.
                 *
                 * Claim an existing migrated row first. Only if it does not exist do we insert a
                 * new row. Both operations remain compare-and-swap guarded, so concurrent writers
                 * cannot both succeed.
                 */
                String migratedRowSql = "UPDATE " + table("pages") + " SET data = ?, revision = 1 "
                        + "WHERE uuid = ? AND page = ? AND revision = 0";
                try (PreparedStatement ps = c.prepareStatement(migratedRowSql)) {
                    ps.setString(1, json);
                    ps.setString(2, uuid.toString());
                    ps.setInt(3, page);
                    if (ps.executeUpdate() == 1) return new SaveResult.Success(1);
                }

                try (PreparedStatement ps = c.prepareStatement(insertIfAbsentSql())) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, page);
                    ps.setString(3, json);
                    if (ps.executeUpdate() == 1) return new SaveResult.Success(1);
                }
                // Row already existed (created by a concurrent writer) — this attempt lost the race.
                return new SaveResult.Conflict(currentRevision(c, uuid, page));
            }
            String sql = "UPDATE " + table("pages") + " SET data = ?, revision = revision + 1 "
                    + "WHERE uuid = ? AND page = ? AND revision = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, json);
                ps.setString(2, uuid.toString());
                ps.setInt(3, page);
                ps.setLong(4, expectedRevision);
                if (ps.executeUpdate() == 1) return new SaveResult.Success(expectedRevision + 1);
            }
            return new SaveResult.Conflict(currentRevision(c, uuid, page));
    }

    @Override
    public long advanceFencingToken(UUID uuid) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement insert = c.prepareStatement(insertFenceIfAbsentSql())) {
                    insert.setString(1, uuid.toString());
                    insert.executeUpdate();
                }
                long current = lockCurrentFence(c, uuid);
                if (current == Long.MAX_VALUE) throw new SQLException("Fencing token exhausted for " + uuid);
                long next = current + 1;
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE " + table("player_fence") + " SET fence = ? WHERE uuid = ?")) {
                    update.setLong(1, next);
                    update.setString(2, uuid.toString());
                    if (update.executeUpdate() != 1) throw new SQLException("Could not advance fencing token");
                }
                c.commit();
                return next;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    @Override
    public SaveResult savePageIfRevisionAndFence(
            UUID uuid, int page, ItemStack[] items, long expectedRevision, long fencingToken) {
        String json = serialize(items);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long currentFence = lockCurrentFence(c, uuid);
                if (currentFence != fencingToken) {
                    c.rollback();
                    return new SaveResult.StaleFence(currentFence);
                }
                SaveResult result = savePageIfRevision(c, uuid, page, json, expectedRevision);
                if (result instanceof SaveResult.Success) c.commit();
                else c.rollback();
                return result;
            } catch (SQLException e) {
                c.rollback();
                e.printStackTrace();
                return new SaveResult.Failure(e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return new SaveResult.Failure(e.getMessage());
        }
    }

    private long lockCurrentFence(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT fence FROM " + table("player_fence") + " WHERE uuid = ? FOR UPDATE")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Missing fencing row for " + uuid);
                return rs.getLong(1);
            }
        }
    }

    private long currentRevision(Connection c, UUID uuid, int page) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT revision FROM " + table("pages") + " WHERE uuid = ? AND page = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    void lockPage(Connection c, UUID uuid, int page) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT revision FROM " + table("pages") + " WHERE uuid = ? AND page = ? FOR UPDATE")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            try (ResultSet ignored = ps.executeQuery()) {
                // The row lock (or InnoDB next-key lock when absent) is the purpose of this query.
            }
        }
    }

    @Override
    public void savePage(UUID uuid, int page, ItemStack[] items) {
        for (int attempt = 0; attempt < 5; attempt++) {
            long revision = loadPageWithRevision(uuid, page).revision();
            SaveResult result = savePageIfRevision(uuid, page, items, revision);
            if (result instanceof SaveResult.Success) return;
            if (!(result instanceof SaveResult.Conflict)) return;
        }
        // Exhausted retries against a concurrent writer on the exact same page — extremely rare;
        // the caller's write is dropped rather than risking a lost update.
    }

    @Override
    public void clearPage(UUID uuid, int page) {
        String sql = "DELETE FROM " + table("pages") + " WHERE uuid = ? AND page = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int saveBackup(UUID uuid, int page, long revision, String reason, ItemStack[] items) {
        String sql = "INSERT INTO " + table("backups")
                + " (uuid, page, revision, reason, created_at, data) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.setLong(3, revision);
            ps.setString(4, reason);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, serialize(items));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    @Override
    public void pruneBackups(UUID uuid, int page, int keep) {
        // Delete every backup for (uuid, page) except the `keep` most recent ones. The inner SELECT
        // is wrapped in an extra derived table (`keep_ids`) because MySQL refuses to SELECT FROM the
        // very table a DELETE targets, even inside a subquery - SQLite tolerates either form fine.
        String backups = table("backups");
        String sql = "DELETE FROM " + backups + " WHERE uuid = ? AND page = ? AND id NOT IN ("
                + "SELECT id FROM (SELECT id FROM " + backups + " WHERE uuid = ? AND page = ? "
                + "ORDER BY created_at DESC LIMIT ?) AS keep_ids)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.setString(3, uuid.toString());
            ps.setInt(4, page);
            ps.setInt(5, Math.max(0, keep));
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<BackupRecord> listBackups(UUID uuid) {
        List<BackupRecord> result = new ArrayList<>();
        String sql = "SELECT id, page, revision, reason, created_at FROM " + table("backups")
                + " WHERE uuid = ? ORDER BY created_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BackupRecord(rs.getInt("id"), uuid, rs.getInt("page"), rs.getLong("revision"),
                            rs.getString("reason"), rs.getLong("created_at")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public BackupRecord getBackup(int id) {
        String sql = "SELECT uuid, page, revision, reason, created_at FROM " + table("backups") + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new BackupRecord(id, UUID.fromString(rs.getString("uuid")), rs.getInt("page"),
                            rs.getLong("revision"), rs.getString("reason"), rs.getLong("created_at"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public ItemStack[] loadBackupItems(int id) {
        String sql = "SELECT data FROM " + table("backups") + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return deserialize(rs.getString("data"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public int countUsedPages(UUID uuid) {
        String sql = "SELECT COUNT(*) FROM " + table("pages") + " WHERE uuid = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public Map<Integer, Integer> countPageItems(UUID uuid) {
        Map<Integer, Integer> result = new HashMap<>();
        String sql = "SELECT page, data FROM " + table("pages") + " WHERE uuid = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonArray arr = GSON.fromJson(rs.getString("data"), JsonArray.class);
                    int count = 0;
                    for (JsonElement el : arr) if (!el.isJsonNull()) count++;
                    result.put(rs.getInt("page"), count);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public int getExtraPages(UUID uuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT extra FROM " + table("extra") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    @Override
    public void addExtraPages(UUID uuid, int amount) {
        setExtraPages(uuid, Math.max(0, getExtraPages(uuid) + amount));
    }

    @Override
    public void removeExtraPages(UUID uuid, int amount) {
        setExtraPages(uuid, Math.max(0, getExtraPages(uuid) - amount));
    }

    @Override
    public void setExtraPages(UUID uuid, int amount) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO " + table("extra") + " (uuid, extra) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(0, amount));
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override
    public boolean isMigrated(UUID uuid, String type) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM " + table("migrated") + " WHERE uuid = ? AND type = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    @Override
    public void markMigrated(UUID uuid, String type) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR IGNORE INTO " + table("migrated") + " (uuid, type) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override
    public void unmarkMigrated(UUID uuid, String type) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + table("migrated") + " WHERE uuid = ? AND type = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    protected String serialize(ItemStack[] items) {
        JsonArray arr = new JsonArray();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                arr.add(JsonNull.INSTANCE);
            } else {
                arr.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
        }
        return GSON.toJson(arr);
    }

    private ItemStack[] deserialize(String json) {
        ItemStack[] items = new ItemStack[PAGE_SIZE];
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        for (int i = 0; i < Math.min(arr.size(), PAGE_SIZE); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonNull()) {
                try {
                    items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(el.getAsString()));
                } catch (Exception ignored) {}
            }
        }
        return items;
    }
}
