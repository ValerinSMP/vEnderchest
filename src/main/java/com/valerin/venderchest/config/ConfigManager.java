package com.valerin.venderchest.config;

import com.valerin.venderchest.VEnderchest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private final VEnderchest plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration guiMain;
    private FileConfiguration guiEnderchest;

    private Set<Material> blacklist;

    public ConfigManager(VEnderchest plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        saveResource("messages.yml");
        saveResource("gui/main.yml");
        saveResource("gui/enderchest.yml");

        messages = load("messages.yml");
        guiMain = load("gui/main.yml");
        guiEnderchest = load("gui/enderchest.yml");

        blacklist = new HashSet<>();
        for (String entry : config.getStringList("blacklist")) {
            // each entry is a map under "blacklist" list with key "material"
        }
        // Re-read as section list
        blacklist.clear();
        var list = config.getMapList("blacklist");
        for (var map : list) {
            Object mat = map.get("material");
            if (mat != null) {
                Material m = Material.matchMaterial(mat.toString());
                if (m != null) blacklist.add(m);
            }
        }
    }

    private void saveResource(String path) {
        File f = new File(plugin.getDataFolder(), path);
        if (!f.exists()) plugin.saveResource(path, false);
    }

    private FileConfiguration load(String path) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), path));
    }

    // --- config.yml ---

    public int getMaxPages() {
        return config.getInt("max-pages", 9);
    }

    public int getDefaultPages() {
        return config.getInt("default-pages", 1);
    }

    public int getAutosaveMinutes() {
        return config.getInt("autosave-minutes", 5);
    }

    public String getDbType() {
        return config.getString("database.type", "sqlite");
    }

    public String getSqliteFile() {
        return config.getString("database.sqlite.file", "enderchest.db");
    }

    public String getMysqlHost() { return config.getString("database.mysql.host", "localhost"); }
    public int getMysqlPort() { return config.getInt("database.mysql.port", 3306); }
    public String getMysqlDatabase() { return config.getString("database.mysql.database", "venderchest"); }
    public String getMysqlUsername() { return config.getString("database.mysql.username", "root"); }
    public String getMysqlPassword() { return config.getString("database.mysql.password", ""); }
    public int getMysqlPoolSize() { return config.getInt("database.mysql.pool-size", 5); }

    public boolean isBlacklisted(Material material) {
        return blacklist.contains(material);
    }

    // --- messages.yml ---

    public Component msg(String key, TagResolver... resolvers) {
        String raw = messages.getString(key, "<red>Missing message: " + key);
        String prefix = messages.getString("prefix", "");
        return mm.deserialize(prefix + raw, resolvers);
    }

    public Component msgNoPrefix(String key, TagResolver... resolvers) {
        String raw = messages.getString(key, "<red>Missing message: " + key);
        return mm.deserialize(raw, resolvers);
    }

    // --- gui/main.yml ---

    public FileConfiguration getGuiMain() { return guiMain; }

    // --- gui/enderchest.yml ---

    public FileConfiguration getGuiEnderchest() { return guiEnderchest; }

    // --- helpers ---

    public int getMaxPages(org.bukkit.entity.Player player) {
        int cap = getMaxPages();
        for (int n = cap; n >= 1; n--) {
            if (player.hasPermission("venderchest.pages." + n)) return n;
        }
        return getDefaultPages();
    }

    public MiniMessage getMM() { return mm; }

    /** Deserializa MiniMessage y fuerza italic=false para nombres/lore de ítems de GUI. */
    public Component parse(String text) {
        return mm.deserialize(text)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
