package com.valerin.venderchest.storage;

import com.valerin.venderchest.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class MysqlStorage extends AbstractJdbcStorage {

    private final ConfigManager config;

    public MysqlStorage(ConfigManager config) {
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
        return "INSERT IGNORE INTO ec_pages (uuid, page, data, revision) VALUES (?, ?, ?, 1)";
    }

    @Override
    protected String backupsTableSql() {
        return """
            CREATE TABLE IF NOT EXISTS ec_backups (
                id INT NOT NULL AUTO_INCREMENT,
                uuid VARCHAR(36) NOT NULL,
                page TINYINT NOT NULL,
                revision BIGINT NOT NULL,
                reason VARCHAR(32) NOT NULL,
                created_at BIGINT NOT NULL,
                data MEDIUMTEXT NOT NULL,
                PRIMARY KEY (id)
            )
            """;
    }

    @Override
    public void init() throws SQLException {
        dataSource = createDataSource();
        try (Connection c = dataSource.getConnection();
             var stmt = c.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ec_pages (
                    uuid VARCHAR(36) NOT NULL,
                    page TINYINT NOT NULL,
                    data MEDIUMTEXT NOT NULL,
                    PRIMARY KEY (uuid, page)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ec_extra (
                    uuid VARCHAR(36) PRIMARY KEY,
                    extra INT NOT NULL DEFAULT 0
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ec_migrated (
                    uuid VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    PRIMARY KEY (uuid, type)
                )
                """);
            stmt.execute(backupsTableSql());
            try (var alterStmt = c.createStatement()) {
                alterStmt.execute("ALTER TABLE ec_pages ADD COLUMN revision BIGINT NOT NULL DEFAULT 0");
            } catch (SQLException alreadyExists) {
                // Column already present from a previous startup.
            }
        }
    }

    @Override
    public void markMigrated(java.util.UUID uuid, String type) {
        try (java.sql.Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "INSERT IGNORE INTO ec_migrated (uuid, type) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) { e.printStackTrace(); }
    }

    @Override
    public void setExtraPages(java.util.UUID uuid, int amount) {
        String sql = "INSERT INTO ec_extra (uuid, extra) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE extra = VALUES(extra)";
        try (java.sql.Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(0, amount));
            ps.executeUpdate();
        } catch (java.sql.SQLException e) { e.printStackTrace(); }
    }
}
