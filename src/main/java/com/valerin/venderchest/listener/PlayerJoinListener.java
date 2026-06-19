package com.valerin.venderchest.listener;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.migration.MigrationManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final VEnderchest plugin;
    private final MigrationManager migrationManager;

    public PlayerJoinListener(VEnderchest plugin, MigrationManager migrationManager) {
        this.plugin           = plugin;
        this.migrationManager = migrationManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        // Capture vanilla items on main thread (no DB, instant), then do all DB work async
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            var vanillaItems = player.getEnderChest().getContents().clone();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> migrationManager.onJoin(player.getUniqueId(), vanillaItems));
        }, 1L);
    }
}
