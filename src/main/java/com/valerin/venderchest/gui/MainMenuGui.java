package com.valerin.venderchest.gui;

import com.valerin.venderchest.config.ConfigManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class MainMenuGui {

    private final ConfigManager config;

    public MainMenuGui(ConfigManager config) {
        this.config = config;
    }

    public Inventory build(Player player, java.util.Map<Integer, Integer> itemCounts) {
        FileConfiguration cfg = config.getGuiMain();
        int rows = cfg.getInt("rows", 3);
        Component title = config.getMM().deserialize(cfg.getString("title", "vEnderchest"));

        Inventory inv = Bukkit.createInventory(null, rows * 9, title);

        int maxPages    = config.getMaxPages(player);
        int absoluteMax = config.getMaxPages();

        List<Integer> pageSlots = parseSlots(cfg.getStringList("page-slots"));

        // Filler
        ItemStack filler = buildFiller(cfg);
        for (int slot : parseSlots(getFillerSlotEntries(cfg))) {
            if (slot >= 0 && slot < rows * 9) inv.setItem(slot, filler);
        }

        // Close button
        ConfigurationSection closeSec = cfg.getConfigurationSection("close");
        if (closeSec != null) {
            int closeSlot = closeSec.getInt("slot", 49);
            if (closeSlot >= 0 && closeSlot < rows * 9) {
                inv.setItem(closeSlot, buildSimpleItem(closeSec));
            }
        }

        // Page items
        for (int i = 0; i < Math.min(pageSlots.size(), absoluteMax); i++) {
            int slot = pageSlots.get(i);
            if (slot < 0 || slot >= rows * 9) continue;
            int pageNum = i + 1;
            int items = itemCounts.getOrDefault(pageNum, 0);
            inv.setItem(slot, pageNum <= maxPages
                    ? buildPageItem(cfg, pageNum, true, items)
                    : buildPageItem(cfg, pageNum, false, items));
        }

        return inv;
    }

    private String replacePlaceholders(String s, int page, int items) {
        return s.replace("{page}", String.valueOf(page))
                .replace("{items}", String.valueOf(items))
                .replace("{max_items}", "45");
    }

    /** Parses a list of slot entries — each can be "N" or "N-M" range. */
    public static List<Integer> parseSlots(List<String> entries) {
        List<Integer> slots = new ArrayList<>();
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.contains("-")) {
                String[] parts = entry.split("-", 2);
                try {
                    int from = Integer.parseInt(parts[0].trim());
                    int to   = Integer.parseInt(parts[1].trim());
                    for (int s = from; s <= to; s++) slots.add(s);
                } catch (NumberFormatException ignored) {}
            } else {
                try { slots.add(Integer.parseInt(entry)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return slots;
    }

    private List<String> getFillerSlotEntries(FileConfiguration cfg) {
        ConfigurationSection sec = cfg.getConfigurationSection("filler");
        return sec != null ? sec.getStringList("slots") : List.of();
    }

    private ItemStack buildPageItem(FileConfiguration cfg, int page, boolean available, int items) {
        String path = available ? "page-item.available" : "page-item.locked";
        ConfigurationSection sec = cfg.getConfigurationSection(path);

        Material mat = available ? Material.PURPLE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        if (sec != null) {
            Material m = Material.matchMaterial(sec.getString("material", mat.name()));
            if (m != null) mat = m;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (sec != null) {
            String nameRaw = replacePlaceholders(sec.getString("name", "Página " + page), page, items);
            meta.displayName(config.parse(nameRaw));

            List<String> loreRaw = sec.getStringList("lore");
            if (!loreRaw.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreRaw)
                    lore.add(config.parse(replacePlaceholders(line, page, items)));
                meta.lore(lore);
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildFiller(FileConfiguration cfg) {
        ConfigurationSection sec = cfg.getConfigurationSection("filler");
        Material mat = Material.BLACK_STAINED_GLASS_PANE;
        if (sec != null) {
            Material m = Material.matchMaterial(sec.getString("material", "BLACK_STAINED_GLASS_PANE"));
            if (m != null) mat = m;
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            if (sec != null && sec.getBoolean("hide-tooltip", false)) {
                meta.setHideTooltip(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public int getCloseSlot() {
        ConfigurationSection sec = config.getGuiMain().getConfigurationSection("close");
        return sec != null ? sec.getInt("slot", 49) : 49;
    }

    ItemStack buildSimpleItem(ConfigurationSection sec) {
        Material mat = Material.BARRIER;
        Material m = Material.matchMaterial(sec.getString("material", "BARRIER"));
        if (m != null) mat = m;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(config.parse(sec.getString("name", " ")));
            List<String> loreRaw = sec.getStringList("lore");
            if (!loreRaw.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreRaw) lore.add(config.parse(line));
                meta.lore(lore);
            }
            if (sec.getBoolean("hide-tooltip", false)) meta.setHideTooltip(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Returns page number (1-based) for the given slot, or -1 if not a page slot. */
    public int getPageForSlot(int slot) {
        List<Integer> pageSlots = parseSlots(config.getGuiMain().getStringList("page-slots"));
        int idx = pageSlots.indexOf(slot);
        return idx >= 0 ? idx + 1 : -1;
    }
}
