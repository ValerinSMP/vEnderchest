package com.valerin.venderchest.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MigratedRevisionZeroJdbcTest {

    @TempDir
    Path tempDir;

    @Test
    void existingPreRevisionRowCanBeSavedExactlyOnce() throws Exception {
        Path database = tempDir.resolve("migrated.db");
        String jdbcUrl = "jdbc:sqlite:" + database;
        UUID owner = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ec_pages (
                        uuid VARCHAR(36) NOT NULL,
                        page TINYINT NOT NULL,
                        data TEXT NOT NULL,
                        PRIMARY KEY (uuid, page)
                    )
                    """);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO ec_pages (uuid, page, data) VALUES (?, ?, ?)")) {
                insert.setString(1, owner.toString());
                insert.setInt(2, 1);
                insert.setString(3, emptyPageJson());
                insert.executeUpdate();
            }
        }

        TestStorage storage = new TestStorage(jdbcUrl);
        storage.init();
        try {
            Storage.PageRecord migrated = storage.loadPageWithRevision(owner, 1);
            assertEquals(0, migrated.revision());

            Storage.SaveResult first = storage.savePageIfRevision(
                    owner, 1, new ItemStack[45], migrated.revision());
            assertEquals(1, assertInstanceOf(Storage.SaveResult.Success.class, first).newRevision());
            assertEquals(1, storage.loadPageWithRevision(owner, 1).revision());

            Storage.SaveResult staleReplay = storage.savePageIfRevision(
                    owner, 1, new ItemStack[45], 0);
            assertEquals(1,
                    assertInstanceOf(Storage.SaveResult.Conflict.class, staleReplay).currentRevision());
        } finally {
            storage.close();
        }
    }

    @Test
    void absentRowStillUsesRevisionOneOnFirstSave() throws Exception {
        Path database = tempDir.resolve("fresh.db");
        String jdbcUrl = "jdbc:sqlite:" + database;
        UUID owner = UUID.randomUUID();

        TestStorage storage = new TestStorage(jdbcUrl);
        storage.init();
        try {
            Storage.SaveResult result =
                    storage.savePageIfRevision(owner, 2, new ItemStack[45], 0);
            assertEquals(1, assertInstanceOf(Storage.SaveResult.Success.class, result).newRevision());
            assertEquals(1, storage.loadPageWithRevision(owner, 2).revision());
        } finally {
            storage.close();
        }
    }

    private static String emptyPageJson() {
        return "[" + "null,".repeat(44) + "null]";
    }

    private static final class TestStorage extends AbstractJdbcStorage {
        private final String jdbcUrl;

        private TestStorage(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        protected HikariDataSource createDataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setMaximumPoolSize(1);
            return new HikariDataSource(config);
        }
    }
}
