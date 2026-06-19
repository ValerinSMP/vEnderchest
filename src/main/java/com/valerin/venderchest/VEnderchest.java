package com.valerin.venderchest;

import com.valerin.venderchest.command.EcAdminCommand;
import com.valerin.venderchest.command.EcCommand;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.gui.GuiManager;
import com.valerin.venderchest.hook.PlaceholderHook;
import com.valerin.venderchest.listener.GuiListener;
import com.valerin.venderchest.listener.InterceptListener;
import com.valerin.venderchest.listener.PlayerJoinListener;
import com.valerin.venderchest.migration.MigrationManager;
import com.valerin.venderchest.storage.MysqlStorage;
import com.valerin.venderchest.storage.SqliteStorage;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class VEnderchest extends JavaPlugin {

    private static VEnderchest instance;

    private ConfigManager    configManager;
    private Storage          storage;
    private GuiManager       guiManager;
    private MigrationManager migrationManager;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);

        try {
            storage = switch (configManager.getDbType().toLowerCase()) {
                case "mysql" -> new MysqlStorage(configManager);
                default -> new SqliteStorage(configManager, getDataFolder());
            };
            storage.init();
            configManager.setStorage(storage);
        } catch (SQLException e) {
            getLogger().severe("Database init failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        guiManager = new GuiManager(this, storage, configManager);

        migrationManager = new MigrationManager(storage, getDataFolder(), configManager.getMaxPages(), getLogger());

        getServer().getPluginManager().registerEvents(new InterceptListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new GuiListener(guiManager, configManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, migrationManager), this);

        var ecCmd = getCommand("ec");
        if (ecCmd != null) {
            var executor = new EcCommand(guiManager, configManager);
            ecCmd.setExecutor(executor);
            ecCmd.setTabCompleter(executor);
        }

        var ecAdminCmd = getCommand("ecadmin");
        if (ecAdminCmd != null) {
            var executor = new EcAdminCommand(this, guiManager, storage, configManager);
            ecAdminCmd.setExecutor(executor);
            ecAdminCmd.setTabCompleter(executor);
        }

        long interval = (long) configManager.getAutosaveMinutes() * 60 * 20;
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> guiManager.saveAllDirty(), interval, interval);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(configManager, storage).register();
            getLogger().info("PlaceholderAPI hook registered.");
        }

        getLogger().info("vEnderchest enabled.");
    }

    @Override
    public void onDisable() {
        if (migrationManager != null) migrationManager.close();
        if (guiManager != null) guiManager.saveAllDirty();
        if (storage != null) storage.close();
        getLogger().info("vEnderchest disabled.");
    }

    public void reload() {
        guiManager.closeAll();   // save dirty + close open GUIs
        configManager.reload();  // reload all YMLs
    }

    public static VEnderchest getInstance() { return instance; }
    public ConfigManager    getConfigManager()    { return configManager; }
    public GuiManager       getGuiManager()       { return guiManager; }
    public Storage          getStorage()          { return storage; }
    public MigrationManager getMigrationManager() { return migrationManager; }
}
