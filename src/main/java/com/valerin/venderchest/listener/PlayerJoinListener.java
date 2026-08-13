package com.valerin.venderchest.listener;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.migration.MigrationManager;
import com.valerin.venderchest.storage.StorageAccessGate;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.function.BooleanSupplier;

public class PlayerJoinListener implements Listener {

    private final VEnderchest plugin;
    private final MigrationManager migrationManager;
    private final BooleanSupplier migrationAllowed;
    private final StorageAccessGate storageGate;

    public PlayerJoinListener(VEnderchest plugin, MigrationManager migrationManager,
                              BooleanSupplier migrationAllowed, StorageAccessGate storageGate) {
        this.plugin           = plugin;
        this.migrationManager = migrationManager;
        this.migrationAllowed = migrationAllowed;
        this.storageGate = storageGate;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!migrationAllowed.getAsBoolean()) return;
        var player = event.getPlayer();
        // Capture vanilla items on main thread (no DB, instant), then do all DB work async
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!migrationAllowed.getAsBoolean()) return;
            var vanillaItems = player.getEnderChest().getContents().clone();
            if (!storageGate.tryBegin()) return;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    migrationManager.onJoin(player.getUniqueId(), vanillaItems);
                } finally {
                    storageGate.end();
                }
            });
        }, 1L);
    }
}
