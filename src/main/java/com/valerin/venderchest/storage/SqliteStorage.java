package com.valerin.venderchest.storage;

import com.valerin.venderchest.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;

public class SqliteStorage extends AbstractJdbcStorage {

    private final ConfigManager config;
    private final File dataFolder;

    public SqliteStorage(ConfigManager config, File dataFolder) {
        super(config.getTablePrefix());
        this.config = config;
        this.dataFolder = dataFolder;
    }

    @Override
    protected HikariDataSource createDataSource() {
        File dbFile = new File(dataFolder, config.getSqliteFile());
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hc.setMaximumPoolSize(1); // SQLite is single-writer
        hc.setConnectionTestQuery("SELECT 1");
        hc.setPoolName("vEnderchest-SQLite");
        hc.addDataSourceProperty("journal_mode", "WAL");
        hc.addDataSourceProperty("synchronous", "NORMAL");
        return new HikariDataSource(hc);
    }
}
