package com.valerin.venderchest.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the ec_backups table (save/prune/list/get) against a real SQLite database. */
class BackupStorageJdbcTest {

    @TempDir
    Path tempDir;

    @Test
    void saveListAndLoadRoundTrip() throws Exception {
        TestStorage storage = new TestStorage("jdbc:sqlite:" + tempDir.resolve("backups.db"));
        storage.init();
        try {
            UUID owner = UUID.randomUUID();
            int id = storage.saveBackup(owner, 1, 3, "COMMIT", new ItemStack[45]);
            assertTrue(id > 0);

            List<Storage.BackupRecord> backups = storage.listBackups(owner);
            assertEquals(1, backups.size());
            assertEquals(1, backups.get(0).page());
            assertEquals(3, backups.get(0).revision());
            assertEquals("COMMIT", backups.get(0).reason());

            Storage.BackupRecord fetched = storage.getBackup(id);
            assertNotNull(fetched);
            assertEquals(owner, fetched.uuid());

            assertNotNull(storage.loadBackupItems(id));
            assertNull(storage.getBackup(id + 999), "a nonexistent id must return null, not throw");
        } finally {
            storage.close();
        }
    }

    @Test
    void pruneKeepsOnlyTheMostRecentPerVault() throws Exception {
        TestStorage storage = new TestStorage("jdbc:sqlite:" + tempDir.resolve("prune.db"));
        storage.init();
        try {
            UUID owner = UUID.randomUUID();
            int[] ids = new int[5];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = storage.saveBackup(owner, 1, i, "COMMIT", new ItemStack[45]);
                Thread.sleep(2); // created_at must strictly increase for the ORDER BY to be deterministic
            }
            // A different vault's backups must never be touched by pruning vault 1.
            storage.saveBackup(owner, 2, 0, "COMMIT", new ItemStack[45]);

            storage.pruneBackups(owner, 1, 2);

            List<Storage.BackupRecord> remaining = storage.listBackups(owner);
            long vault1Remaining = remaining.stream().filter(b -> b.page() == 1).count();
            long vault2Remaining = remaining.stream().filter(b -> b.page() == 2).count();
            assertEquals(2, vault1Remaining, "only the 2 most recent vault-1 backups should survive");
            assertEquals(1, vault2Remaining, "pruning vault 1 must not touch vault 2's backups");

            // The two survivors must be the two most recently created ones.
            List<Integer> survivingRevisions = remaining.stream()
                    .filter(b -> b.page() == 1).map(Storage.BackupRecord::revision).sorted().toList()
                    .stream().map(Long::intValue).toList();
            assertEquals(List.of(3, 4), survivingRevisions);
        } finally {
            storage.close();
        }
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
