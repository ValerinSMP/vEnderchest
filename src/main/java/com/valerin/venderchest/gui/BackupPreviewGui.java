package com.valerin.venderchest.gui;

import com.valerin.venderchest.config.ConfigManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Read-only preview of one backup's content, with a confirm-guarded in-GUI restore button. */
public final class BackupPreviewGui {

    public static final int BACK_SLOT = 45;
    public static final int RESTORE_SLOT = 49;
    public static final int CLOSE_SLOT = 53;

    private final ConfigManager config;

    public BackupPreviewGui(ConfigManager config) {
        this.config = config;
    }

    public Inventory build(String targetName, int backupId, int page, ItemStack[] items, boolean confirmPending) {
        Component title = config.parse("<gray>" + targetName + " <yellow>[BACKUP #" + backupId + "] <dark_gray>Pg " + page);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        for (int i = 0; i < Math.min(45, items.length); i++) inv.setItem(i, items[i]);

        ItemStack filler = filler();
        for (int slot = 45; slot <= 53; slot++) inv.setItem(slot, filler);
        inv.setItem(BACK_SLOT, navItem(Material.ARROW, "<yellow>« Volver a la lista"));
        inv.setItem(RESTORE_SLOT, confirmPending
                ? navItem(Material.RED_WOOL, "<red>¿Confirmar restauración? <dark_red>(click de nuevo)")
                : navItem(Material.LIME_WOOL, "<green>Restaurar este backup"));
        inv.setItem(CLOSE_SLOT, navItem(Material.BARRIER, "<red>Cerrar"));
        return inv;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(config.parse(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
