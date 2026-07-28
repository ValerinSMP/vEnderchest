package com.valerin.venderchest.gui;

import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.storage.Storage;
import com.valerin.venderchest.utils.RelativeTime;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Paginated, clickable list of a player's stored vault backups - one item per backup. */
public final class BackupListGui {

    public static final int PAGE_SIZE = 45;
    public static final int PREV_SLOT = 45;
    public static final int CLOSE_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private final ConfigManager config;

    public BackupListGui(ConfigManager config) {
        this.config = config;
    }

    public int totalPages(List<Storage.BackupRecord> backups) {
        return Math.max(1, (int) Math.ceil(backups.size() / (double) PAGE_SIZE));
    }

    public Inventory build(String targetName, List<Storage.BackupRecord> backups, int page) {
        int totalPages = totalPages(backups);
        Component title = config.parse("<gray>Backups de <white>" + targetName
                + " <dark_gray>(" + (page + 1) + "/" + totalPages + ")");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, backups.size());
        for (int i = from; i < to; i++) {
            inv.setItem(i - from, buildBackupItem(backups.get(i)));
        }

        ItemStack filler = filler();
        for (int slot = 45; slot <= 53; slot++) inv.setItem(slot, filler);
        if (page > 0) inv.setItem(PREV_SLOT, navItem(Material.ARROW, "<yellow>« Página anterior"));
        if (page < totalPages - 1) inv.setItem(NEXT_SLOT, navItem(Material.ARROW, "<yellow>Página siguiente »"));
        inv.setItem(CLOSE_SLOT, navItem(Material.BARRIER, "<red>Cerrar"));
        return inv;
    }

    /** Backup id for a clicked content slot on the given page, or -1 if empty/out of range. */
    public int backupIdForSlot(List<Storage.BackupRecord> backups, int page, int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) return -1;
        int index = page * PAGE_SIZE + slot;
        return index < backups.size() ? backups.get(index).id() : -1;
    }

    private ItemStack buildBackupItem(Storage.BackupRecord backup) {
        ItemStack item = new ItemStack(Material.ENDER_CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(config.parse("<yellow>Backup #" + backup.id()));
        meta.lore(List.of(
                config.parse("<gray>Página <white>" + backup.page()),
                config.parse("<gray>Revisión <white>" + backup.revision()),
                config.parse("<gray>Motivo: <white>" + backup.reason()),
                config.parse("<gray>" + RelativeTime.since(backup.createdAtMillis())),
                Component.empty(),
                config.parse("<green>Click para previsualizar")
        ));
        item.setItemMeta(meta);
        return item;
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
