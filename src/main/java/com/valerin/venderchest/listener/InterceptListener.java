package com.valerin.venderchest.listener;

import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.gui.GuiManager;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
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

    /**
     * Explicit, server-authoritative commit-and-close on disconnect — does not assume
     * {@code InventoryCloseEvent} has already fired (that assumption is not guaranteed across
     * Bukkit forks and was the source of a latent lost-write bug). Safe to run even if
     * {@code onClose} already handled the same session: {@link GuiManager#handleDisconnect} only
     * ever performs the commit once per session (compare-and-swapped), so redundant calls no-op.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        guiManager.detachCrossCursorBeforeDisconnect(event.getPlayer());
        guiManager.handleDisconnect(event.getPlayer().getUniqueId(), CloseReason.LOGOUT);
    }

    /** Fires before the quit pipeline for a kicked player; gives a more precise close reason than LOGOUT. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        guiManager.detachCrossCursorBeforeDisconnect(event.getPlayer());
        guiManager.handleDisconnect(event.getPlayer().getUniqueId(), CloseReason.KICK);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        guiManager.parkCrossCursorBeforeDeath(event.getEntity());
        guiManager.handleDisconnect(event.getEntity().getUniqueId(), CloseReason.CLIENT_CLOSE);
    }
}
