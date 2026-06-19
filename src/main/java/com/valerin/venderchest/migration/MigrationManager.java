package com.valerin.venderchest.migration;

import com.valerin.venderchest.storage.Storage;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

public class MigrationManager {

    private final VanillaMigrator  vanillaMigrator;
    private final AxVaultsMigrator axVaultsMigrator;

    public MigrationManager(Storage storage, File dataFolder, int maxPages, Logger log) {
        vanillaMigrator = new VanillaMigrator(storage, maxPages, log);

        File importDir  = new File(dataFolder, "import");
        importDir.mkdirs();
        File axVaultsDb = new File(importDir, "axvaults.mv.db");

        if (axVaultsDb.exists()) {
            axVaultsMigrator = new AxVaultsMigrator(storage, axVaultsDb, maxPages, log);
            try {
                axVaultsMigrator.open();
            } catch (SQLException e) {
                log.severe("[vEnderchest] Could not open axVaults import DB: " + e.getMessage());
            }
        } else {
            axVaultsMigrator = null;
        }
    }

    /** Called from async thread. vanillaItems were captured on main thread. */
    public void onJoin(UUID uuid, ItemStack[] vanillaItems) {
        // axVaults first (paid content, higher priority)
        if (axVaultsMigrator != null && axVaultsMigrator.isOpen()) {
            axVaultsMigrator.migrate(uuid);
        }
        vanillaMigrator.migrate(uuid, vanillaItems);
    }

    public void close() {
        if (axVaultsMigrator != null) axVaultsMigrator.close();
    }
}
