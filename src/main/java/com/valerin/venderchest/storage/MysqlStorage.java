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
    protected String upsertSql() {
        return "INSERT INTO ec_pages (uuid, page, data) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE data = VALUES(data)";
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
        }
    }
}
