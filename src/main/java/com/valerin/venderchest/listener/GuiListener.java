package com.valerin.venderchest.listener;

import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.gui.EnderchestGui;
import com.valerin.venderchest.gui.GuiManager;
import com.valerin.venderchest.model.OpenSession;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class GuiListener implements Listener {

    private final GuiManager guiManager;
    private final ConfigManager config;

    public GuiListener(GuiManager guiManager, ConfigManager config) {
        this.guiManager = guiManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        OpenSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;

        Inventory topInv = event.getView().getTopInventory();
        if (!session.getInventory().equals(topInv)) return;

        int rawSlot = event.getRawSlot();
        int topSize = topInv.getSize();
        boolean clickedTop = rawSlot >= 0 && rawSlot < topSize;

        // COLLECT_TO_CURSOR (double-click): could grab items from nav/GUI slots — always cancel
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        // ── Main menu (page == -1) ─────────────────────────────────────────
        if (session.getPage() == -1) {
            if (!clickedTop) {
                // Block shift-click into the menu — items would disappear into nav-button slots
                if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY)
                    event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            if (event.getAction() == InventoryAction.NOTHING) return;

            // Close button
            if (rawSlot == guiManager.getMainMenuGui().getCloseSlot()) {
                player.closeInventory();
                return;
            }

            int page = guiManager.getMainMenuGui().getPageForSlot(rawSlot);
            if (page < 1) return;

            int maxPages = config.getMaxPages(player);
            if (page > maxPages) {
                config.playSound(player, "denied");
                player.sendMessage(config.msg("page-locked",
                        Placeholder.unparsed("page", String.valueOf(page))));
                return;
            }
            guiManager.openPage(player, page);
            return;
        }

        // ── Enderchest page ───────────────────────────────────────────────

        // Nav row (slots 45-53): always block
        if (clickedTop && EnderchestGui.NAV_SLOTS.contains(rawSlot)) {
            event.setCancelled(true);
            handleNavClick(player, session, rawSlot);
            return;
        }

        // Shift-click from player inventory into top → could land in nav row
        if (!clickedTop && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (session.isReadOnly()) { event.setCancelled(true); return; }
            if (!session.isAdminView()) {
                ItemStack moved = event.getCurrentItem();
                if (moved != null && config.isBlacklisted(moved.getType())) {
                    event.setCancelled(true);
                    player.sendMessage(config.msg("item-blacklisted"));
                    return;
                }
            }
            session.markDirty();
            return;
        }

        // Click in player inventory half (not shift-click) — allow normally
        if (!clickedTop) return;

        // Content area (slots 0-44)
        if (session.isReadOnly()) {
            event.setCancelled(true);
            return;
        }

        // Blacklist check only for regular players; admins bypass it
        if (!session.isAdminView()) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && config.isBlacklisted(cursor.getType())) {
                event.setCancelled(true);
                config.playSound(player, "denied");
                player.sendMessage(config.msg("item-blacklisted"));
                return;
            }

            if (event.getAction() == InventoryAction.HOTBAR_SWAP
                    || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
                int hotbarSlot = event.getHotbarButton();
                if (hotbarSlot >= 0) {
                    ItemStack hotbarItem = player.getInventory().getItem(hotbarSlot);
                    if (hotbarItem != null && config.isBlacklisted(hotbarItem.getType())) {
                        event.setCancelled(true);
                        config.playSound(player, "denied");
                        player.sendMessage(config.msg("item-blacklisted"));
                        return;
                    }
                }
            }
        }

        session.markDirty();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        OpenSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;

        Inventory topInv = event.getView().getTopInventory();
        if (!session.getInventory().equals(topInv)) return;

        int topSize = topInv.getSize();

        // Check if any dragged slot lands in top inventory
        boolean touchesTop = event.getRawSlots().stream().anyMatch(s -> s < topSize);
        if (!touchesTop) return;

        // Main menu: block all drags into it
        if (session.getPage() == -1) {
            event.setCancelled(true);
            return;
        }

        // Enderchest page: block drags into nav row
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && EnderchestGui.NAV_SLOTS.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }

        if (session.isReadOnly()) {
            event.setCancelled(true);
            return;
        }

        if (!session.isAdminView() && config.isBlacklisted(event.getOldCursor().getType())) {
            event.setCancelled(true);
            config.playSound(player, "denied");
            player.sendMessage(config.msg("item-blacklisted"));
            return;
        }

        session.markDirty();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenSession session = guiManager.getSession(player.getUniqueId());
        if (session == null) return;
        if (!session.getInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (slot < 0 || slot >= topSize) return; // click in player's own inventory — allow

        // Nav row and main menu: always block
        if (session.getPage() == -1 || EnderchestGui.NAV_SLOTS.contains(slot)) {
            event.setCancelled(true);
            return;
        }
        // Read-only sessions: block content area too
        if (session.isReadOnly()) {
            event.setCancelled(true);
            return;
        }
        session.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        // Only play close sound if truly closing (not navigating to another GUI)
        var session = guiManager.getSession(player.getUniqueId());
        if (session != null && session.getInventory().equals(event.getInventory())) {
            config.playSound(player, "close");
        }
        guiManager.onClose(player.getUniqueId(), event.getInventory());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void handleNavClick(Player player, OpenSession session, int slot) {
        var navCfg = config.getGuiEnderchest().getConfigurationSection("navigation");
        int prevSlot  = navCfg != null ? navCfg.getInt("prev-page.slot", 45) : 45;
        int nextSlot  = navCfg != null ? navCfg.getInt("next-page.slot", 53) : 53;
        int homeSlot  = navCfg != null ? navCfg.getInt("home.slot", 49) : 49;

        // For admin sessions the target may be offline, use global max
        int maxPages    = session.isAdminView() ? config.getMaxPages() : config.getMaxPages(player);
        int currentPage = session.getPage();

        if (slot == prevSlot && currentPage > 1) {
            guiManager.navigatePage(player, currentPage - 1);
        } else if (slot == nextSlot && currentPage < maxPages) {
            guiManager.navigatePage(player, currentPage + 1);
        } else if (slot == homeSlot) {
            guiManager.navigateToMainMenu(player);
        }
    }
}
