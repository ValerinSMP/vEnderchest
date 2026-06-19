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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

abstract class AbstractJdbcStorage implements Storage {

    protected HikariDataSource dataSource;
    private static final Gson GSON = new Gson();
    private static final int PAGE_SIZE = 45;

    @Override
    public void init() throws SQLException {
        dataSource = createDataSource();
        try (Connection c = dataSource.getConnection();
             var stmt = c.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ec_pages (
                    uuid VARCHAR(36) NOT NULL,
                    page TINYINT NOT NULL,
                    data TEXT NOT NULL,
                    PRIMARY KEY (uuid, page)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ec_extra (
                    uuid VARCHAR(36) PRIMARY KEY,
                    extra INTEGER NOT NULL DEFAULT 0
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ec_migrated (
                    uuid VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    PRIMARY KEY (uuid, type)
                )
                """);
        }
    }

    protected abstract HikariDataSource createDataSource();

    @Override
    public ItemStack[] loadPage(UUID uuid, int page) {
        String sql = "SELECT data FROM ec_pages WHERE uuid = ? AND page = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return deserialize(rs.getString("data"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new ItemStack[PAGE_SIZE];
    }

    protected String upsertSql() {
        return "INSERT OR REPLACE INTO ec_pages (uuid, page, data) VALUES (?, ?, ?)";
    }

    @Override
    public void savePage(UUID uuid, int page, ItemStack[] items) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(upsertSql())) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.setString(3, serialize(items));
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void clearPage(UUID uuid, int page) {
        String sql = "DELETE FROM ec_pages WHERE uuid = ? AND page = ?";
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
    public int countUsedPages(UUID uuid) {
        String sql = "SELECT COUNT(*) FROM ec_pages WHERE uuid = ?";
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
        String sql = "SELECT page, data FROM ec_pages WHERE uuid = ?";
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
                     "SELECT extra FROM ec_extra WHERE uuid = ?")) {
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
                     "INSERT OR REPLACE INTO ec_extra (uuid, extra) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(0, amount));
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override
    public boolean isMigrated(UUID uuid, String type) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM ec_migrated WHERE uuid = ? AND type = ?")) {
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
                     "INSERT OR IGNORE INTO ec_migrated (uuid, type) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override
    public void unmarkMigrated(UUID uuid, String type) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ec_migrated WHERE uuid = ? AND type = ?")) {
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
