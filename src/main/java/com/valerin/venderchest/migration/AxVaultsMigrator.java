package com.valerin.venderchest.migration;

import com.valerin.venderchest.storage.Storage;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class AxVaultsMigrator {

    private static final String TYPE      = "axvaults";
    private static final int    VAULT_SIZE = 54;

    private final Storage storage;
    private final File    importFile;
    private final int     maxPages;
    private final Logger  log;

    private Connection h2conn;

    public AxVaultsMigrator(Storage storage, File importFile, int maxPages, Logger log) {
        this.storage    = storage;
        this.importFile = importFile;
        this.maxPages   = maxPages;
        this.log        = log;
    }

    public void open() throws SQLException {
        String path = importFile.getAbsolutePath()
                .replace(".mv.db", "")
                .replace("\\", "/");
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 driver not found on classpath", e);
        }

        String[] urls = {
            "jdbc:h2:file:" + path + ";ACCESS_MODE_DATA=r;AUTO_SERVER=FALSE",
            "jdbc:h2:file:" + path + ";AUTO_SERVER=FALSE",
            "jdbc:h2:file:" + path,
        };
        SQLException last = null;
        for (String url : urls) {
            try {
                h2conn = DriverManager.getConnection(url, "sa", "");
                log.info("[vEnderchest] axVaults import database opened (url=" + url + ").");
                return;
            } catch (SQLException e) {
                log.warning("[vEnderchest] axVaults connect attempt failed [errorCode="
                        + e.getErrorCode() + "]: " + e.getMessage() + " — trying next mode...");
                last = e;
            }
        }
        throw new SQLException("All H2 connection modes failed. Last error: " + last.getMessage(), last);
    }

    public void close() {
        if (h2conn != null) try { h2conn.close(); } catch (SQLException ignored) {}
    }

    public boolean isOpen() {
        try { return h2conn != null && !h2conn.isClosed(); } catch (SQLException e) { return false; }
    }

    public void migrate(UUID uuid) {
        if (storage.isMigrated(uuid, TYPE)) return;
        if (!isOpen()) return;

        // Collect every non-air item from every vault
        List<ItemStack> existingShulkers = new ArrayList<>();
        List<ItemStack> regularItems     = new ArrayList<>();

        try (PreparedStatement ps = h2conn.prepareStatement(
                "SELECT id, storage FROM axvaults_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int    vaultId = rs.getInt("id");
                    byte[] blob    = rs.getBytes("storage");
                    if (blob == null) continue;

                    for (ItemStack item : deserializeAll(blob, uuid, vaultId)) {
                        if (item == null || item.getType().isAir()) continue;
                        // Keep existing shulker boxes as-is; pack everything else
                        if (item.getType().name().endsWith("SHULKER_BOX")) {
                            existingShulkers.add(item);
                        } else {
                            regularItems.add(item);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.severe("[vEnderchest] axVaults migration error for " + uuid + ": " + e.getMessage());
            return;
        }

        // Pack regular items into shulker boxes (27 per box)
        List<ItemStack> packedBoxes = ShulkerBoxHelper.packAll(regularItems);

        // Items to place: original shulkers first, then our newly packed boxes
        List<ItemStack> toPlace = new ArrayList<>(existingShulkers);
        toPlace.addAll(packedBoxes);

        int total = regularItems.size() + existingShulkers.size();
        if (total == 0) {
            storage.markMigrated(uuid, TYPE);
            return;
        }

        int lost = ShulkerBoxHelper.placeItems(uuid, toPlace, storage, maxPages, log);
        if (lost > 0) {
            log.warning("[vEnderchest] axVaults: " + uuid + ": " + lost
                    + " shulker box(es) couldn't be placed (all pages full). Items may be lost.");
        } else {
            log.info("[vEnderchest] axVaults: " + uuid + ": " + total + " item(s) from all vaults → "
                    + toPlace.size() + " shulker box(es) placed.");
        }

        storage.markMigrated(uuid, TYPE);
    }

    private ItemStack[] deserializeAll(byte[] blob, UUID uuid, int vaultId) {
        ItemStack[] result = new ItemStack[VAULT_SIZE];
        try {
            DataInputStream dis        = new DataInputStream(new ByteArrayInputStream(blob));
            int             totalSlots = dis.readInt();

            for (int i = 0; i < totalSlots; i++) {
                short len = dis.readShort();
                if (len <= 0) continue;

                byte[] itemBytes = new byte[len & 0xFFFF];
                dis.readFully(itemBytes);

                if (i >= VAULT_SIZE) continue;

                try {
                    result[i] = ItemStack.deserializeBytes(itemBytes);
                } catch (Exception e) {
                    log.warning("[vEnderchest] axVaults: failed slot " + i + " for "
                            + uuid + " vault " + vaultId + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.severe("[vEnderchest] axVaults: blob read error for " + uuid
                    + " vault " + vaultId + ": " + e.getMessage());
        }
        return result;
    }
}
