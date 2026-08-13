package com.valerin.venderchest.storage;

import com.valerin.venderchest.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class MysqlStorage extends AbstractJdbcStorage {

    private final ConfigManager config;

    public MysqlStorage(ConfigManager config) {
        super(config.getTablePrefix());
        this.config = config;
    }

    @Override
    protected HikariDataSource createDataSource() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + config.getMysqlHost() + ":" + config.getMysqlPort()
                + "/" + config.getMysqlDatabase() + "?useSSL=false&autoReconnect=true");
        hc.setUsername(config.getMysqlUsername());
        hc.setPassword(config.getMysqlPassword());
        hc.setMaximumPoolSize(config.getMysqlPoolSize());
        hc.setPoolName("vEnderchest-MySQL");
        return new HikariDataSource(hc);
    }

    @Override
    protected String insertIfAbsentSql() {
        return "INSERT IGNORE INTO " + table("pages") + " (uuid, page, data, revision) VALUES (?, ?, ?, 1)";
    }

    public String configuredTablePrefix() {
        return table("");
    }

    @Override
    protected String insertFenceIfAbsentSql() {
        return "INSERT IGNORE INTO " + table("player_fence") + " (uuid, fence) VALUES (?, 0)";
    }

    @Override
    protected String backupsTableSql() {
        return """
            CREATE TABLE IF NOT EXISTS %s (
                id INT NOT NULL AUTO_INCREMENT,
                uuid VARCHAR(36) NOT NULL,
                page TINYINT NOT NULL,
                revision BIGINT NOT NULL,
                reason VARCHAR(32) NOT NULL,
                created_at BIGINT NOT NULL,
                data MEDIUMTEXT NOT NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB
            """.formatted(table("backups"));
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
                    data MEDIUMTEXT NOT NULL,
                    PRIMARY KEY (uuid, page)
                ) ENGINE=InnoDB
                """.formatted(table("pages")));
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) PRIMARY KEY,
                    extra INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB
                """.formatted(table("extra")));
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    PRIMARY KEY (uuid, type)
                ) ENGINE=InnoDB
                """.formatted(table("migrated")));
            stmt.execute(backupsTableSql());
            try (var alterStmt = c.createStatement()) {
                alterStmt.execute("ALTER TABLE " + table("pages")
                        + " ADD COLUMN revision BIGINT NOT NULL DEFAULT 0");
            } catch (SQLException alreadyExists) {
                // Column already present from a previous startup.
            }
        }
    }

    @Override
    public void markMigrated(java.util.UUID uuid, String type) {
        try (java.sql.Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "INSERT IGNORE INTO " + table("migrated") + " (uuid, type) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) { e.printStackTrace(); }
    }

    @Override
    public void setExtraPages(java.util.UUID uuid, int amount) {
        String sql = "INSERT INTO " + table("extra") + " (uuid, extra) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE extra = VALUES(extra)";
        try (java.sql.Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(0, amount));
            ps.executeUpdate();
        } catch (java.sql.SQLException e) { e.printStackTrace(); }
    }
}
