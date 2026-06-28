package com.valerin.venderchest.listener;

import com.valerin.venderchest.gui.GuiManager;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class InterceptListener implements Listener {

    private final GuiManager guiManager;

    public InterceptListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // Only main hand to avoid double-firing
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        var block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) return;

        var player = event.getPlayer();
        if (player.hasPermission("venderchest.bypass")) return;

        event.setCancelled(true);
        guiManager.openMainMenu(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // onClose fires before quit, but clean up lastPage cache after a while
        guiManager.cleanup(event.getPlayer().getUniqueId());
    }
}
