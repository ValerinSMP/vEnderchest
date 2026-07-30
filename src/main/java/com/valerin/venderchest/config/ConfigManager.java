package com.valerin.venderchest.config;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.format.TextDecoration;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private FileConfiguration sounds;
    private MessageService messageService;

    private Set<Material> blacklist;
    private Storage storage;

    public ConfigManager(VEnderchest plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        load("config.yml");
        plugin.reloadConfig();
        config = plugin.getConfig();

        saveResource("messages.yml");
        saveResource("sounds.yml");
        saveResource("gui/main.yml");
        saveResource("gui/enderchest.yml");

        messages = load("messages.yml");
        if (messageService == null) {
            messageService = new MessageService(messages);
        } else {
            messageService.reload(messages);
        }
        sounds = load("sounds.yml");
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
        File file = new File(plugin.getDataFolder(), path);
        try (var stream = plugin.getResource(path)) {
            if (stream == null) return YamlConfiguration.loadConfiguration(file);
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            defaults.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return loadMerged(file, defaults);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Could not add new defaults to " + path + ": " + e.getMessage());
            return YamlConfiguration.loadConfiguration(file);
        }
    }

    static YamlConfiguration loadMerged(File file, FileConfiguration defaults)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration current = new YamlConfiguration();
        current.options().parseComments(true);
        current.load(file);
        if (mergeMissing(current, defaults)) current.save(file);
        return current;
    }

    static boolean mergeMissing(FileConfiguration current, FileConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || current.contains(key)) continue;
            current.set(key, defaults.get(key));
            current.setComments(key, defaults.getComments(key));
            current.setInlineComments(key, defaults.getInlineComments(key));
            changed = true;
        }
        return changed;
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

    public boolean isAuditConsoleEnabled() {
        return config.getBoolean("audit.console-enabled", true);
    }

    // --- vantidupe / duplication-protection observability ---
    // NOTE: these only control logging/labeling. The duplication protection itself (session
    // serialization + revision compare-and-swap) has no config switch and cannot be disabled here.

    public String getVantidupeServerId() {
        return config.getString("vantidupe.server-id", "default");
    }

    /** Raw string; parsed against {@code VaultAuditLog.Level} by the caller, defaulting to NORMAL on anything unrecognized. */
    public String getVantidupeAuditLevel() {
        return config.getString("vantidupe.audit-level", "NORMAL");
    }

    /** How often (seconds) the in-memory orphaned-session backstop sweep runs. Floored at 10s. */
    public int getOrphanSweepSeconds() {
        return Math.max(10, config.getInt("vantidupe.orphan-sweep-seconds", 60));
    }

    public boolean isWarnConsoleOnConflict() {
        return config.getBoolean("vantidupe.warn-console-on-conflict", true);
    }

    // --- backups ---

    public boolean isBackupsEnabled() {
        return config.getBoolean("backups.enabled", true);
    }

    public int getBackupsKeepPerVault() {
        return Math.max(1, config.getInt("backups.keep-per-vault", 20));
    }

    // --- messages.yml ---

    public Component msg(String key, TagResolver... resolvers) {
        return messageService.component(key, resolvers);
    }

    public Component msgNoPrefix(String key, TagResolver... resolvers) {
        return messageService.componentWithoutPrefix(key, resolvers);
    }

    // --- gui/main.yml ---

    public FileConfiguration getGuiMain() { return guiMain; }

    // --- gui/enderchest.yml ---

    public FileConfiguration getGuiEnderchest() { return guiEnderchest; }

    // --- helpers ---

    public void setStorage(Storage s) { this.storage = s; }

    /** Base pages from VIP permission alone. */
    public int getBasePages(Player player) {
        int cap = getMaxPages();
        for (int n = cap; n >= 1; n--) {
            if (player.hasPermission("venderchest.pages." + n)) return n;
        }
        return getDefaultPages();
    }

    /** Base pages + extra purchased vaults, capped at max-pages. */
    public int getMaxPages(Player player) {
        int base  = getBasePages(player);
        int extra = storage != null ? storage.getExtraPages(player.getUniqueId()) : 0;
        return Math.min(base + extra, getMaxPages());
    }

    public MiniMessage getMM() { return mm; }
    public MessageService getMessageService() { return messageService; }

    public void playSound(Player player, String key) {
        ConfigurationSection sec = sounds.getConfigurationSection(key);
        if (sec == null || !sec.getBoolean("enabled", true)) return;
        String name = sec.getString("sound", "");
        if (name.isEmpty()) return;
        try {
            Sound sound = Sound.valueOf(name.toUpperCase());
            float volume = (float) sec.getDouble("volume", 1.0);
            float pitch  = (float) sec.getDouble("pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {}
    }

    /** Deserializa MiniMessage y fuerza italic=false para nombres/lore de ítems de GUI. */
    public Component parse(String text) {
        return mm.deserialize(text)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
