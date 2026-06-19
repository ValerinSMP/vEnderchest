package com.valerin.venderchest.gui;

import com.valerin.venderchest.config.ConfigManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class EnderchestGui {

    public static final Set<Integer> NAV_SLOTS = Set.of(45, 46, 47, 48, 49, 50, 51, 52, 53);

    private final ConfigManager config;

    public EnderchestGui(ConfigManager config) {
        this.config = config;
    }

    public Inventory build(ItemStack[] content, int page, int maxPages) {
        return build(content, page, maxPages, null);
    }

    public Inventory build(ItemStack[] content, int page, int maxPages, String titleOverride) {
        FileConfiguration cfg = config.getGuiEnderchest();
        String titleRaw = titleOverride != null ? titleOverride
                : cfg.getString("title", "Enderchest ({page}/{max})")
                        .replace("{page}", String.valueOf(page))
                        .replace("{max}", String.valueOf(maxPages));
        Component title = config.getMM().deserialize(titleRaw);

        Inventory inv = Bukkit.createInventory(null, 54, title);

        for (int i = 0; i < 45; i++) {
            if (content != null && i < content.length && content[i] != null)
                inv.setItem(i, content[i]);
        }

        buildNavRow(inv, cfg, page, maxPages);
        return inv;
    }

    private void buildNavRow(Inventory inv, FileConfiguration cfg, int page, int maxPages) {
        ConfigurationSection nav = cfg.getConfigurationSection("navigation");

        // Filler for all nav slots first
        ItemStack filler = buildFiller(nav);
        for (int slot : NAV_SLOTS) inv.setItem(slot, filler);

        int prevSlot = nav != null ? nav.getInt("prev-page.slot", 45) : 45;
        int nextSlot = nav != null ? nav.getInt("next-page.slot", 53) : 53;
        int homeSlot = nav != null ? nav.getInt("home.slot", 49) : 49;

        if (page > 1)       inv.setItem(prevSlot, buildNavItem(nav, "prev-page", page, maxPages));
        if (page < maxPages) inv.setItem(nextSlot, buildNavItem(nav, "next-page", page, maxPages));
        inv.setItem(homeSlot, buildNavItem(nav, "home", page, maxPages));
    }

    private ItemStack buildNavItem(ConfigurationSection nav, String key, int page, int maxPages) {
        if (nav == null) return buildEmptyPane();
        ConfigurationSection sec = nav.getConfigurationSection(key);
        if (sec == null) return buildEmptyPane();

        Material mat = Material.matchMaterial(sec.getString("material", "PURPLE_STAINED_GLASS_PANE"));
        if (mat == null) mat = Material.PURPLE_STAINED_GLASS_PANE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(config.parse(replace(sec.getString("name", " "), page, maxPages)));

        List<String> loreRaw = sec.getStringList("lore");
        if (!loreRaw.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreRaw) lore.add(config.parse(replace(line, page, maxPages)));
            meta.lore(lore);
        }

        if (sec.getBoolean("hide-tooltip", false)) meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildFiller(ConfigurationSection nav) {
        if (nav == null) return buildEmptyPane();
        ConfigurationSection sec = nav.getConfigurationSection("filler");

        Material mat = Material.BLACK_STAINED_GLASS_PANE;
        if (sec != null) {
            Material m = Material.matchMaterial(sec.getString("material", "BLACK_STAINED_GLASS_PANE"));
            if (m != null) mat = m;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            if (sec != null && sec.getBoolean("hide-tooltip", false)) meta.setHideTooltip(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildEmptyPane() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.empty()); item.setItemMeta(meta); }
        return item;
    }

    private String replace(String s, int page, int maxPages) {
        return s.replace("{page}", String.valueOf(page))
                .replace("{max}", String.valueOf(maxPages))
                .replace("{prev}", String.valueOf(page - 1))
                .replace("{next}", String.valueOf(page + 1));
    }

    public static ItemStack[] extractContent(Inventory inv) {
        ItemStack[] content = new ItemStack[45];
        for (int i = 0; i < 45; i++) content[i] = inv.getItem(i);
        return content;
    }
}
