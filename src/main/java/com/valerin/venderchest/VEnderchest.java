package com.valerin.venderchest;

import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.api.VEnderChestApi;
import com.valerin.venderchest.command.EcAdminCommand;
import com.valerin.venderchest.command.EcCommand;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.gui.GuiManager;
import com.valerin.venderchest.hook.PlaceholderHook;
import com.valerin.venderchest.listener.GuiListener;
import com.valerin.venderchest.listener.InterceptListener;
import com.valerin.venderchest.listener.PlayerJoinListener;
import com.valerin.venderchest.migration.MigrationManager;
import com.valerin.venderchest.session.VEnderChestApiImpl;
import com.valerin.venderchest.session.VaultAuditLog;
import com.valerin.venderchest.session.VaultSessionRegistry;
import com.valerin.venderchest.session.VaultTransactionService;
import com.valerin.venderchest.storage.MysqlStorage;
import com.valerin.venderchest.storage.SqliteStorage;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class VEnderchest extends JavaPlugin {

    private static VEnderchest instance;

    private ConfigManager        configManager;
    private Storage              storage;
    private GuiManager           guiManager;
    private MigrationManager     migrationManager;
    private VaultSessionRegistry sessionRegistry;
    private VaultAuditLog        auditLog;
    private VaultTransactionService transactionService;

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

        sessionRegistry = new VaultSessionRegistry();
        auditLog = new VaultAuditLog(getLogger(), parseAuditLevel(configManager.getVantidupeAuditLevel()),
                configManager.isWarnConsoleOnConflict());
        transactionService = new VaultTransactionService(this, storage, sessionRegistry, auditLog,
                configManager.getVantidupeServerId(), configManager.isBackupsEnabled(), configManager.getBackupsKeepPerVault());

        guiManager = new GuiManager(this, storage, configManager, sessionRegistry, transactionService);

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

        // Autosave: runs on the main thread (it reads live Inventory contents); each session's
        // actual DB write is still dispatched asynchronously by VaultTransactionService.
        long autosaveInterval = (long) configManager.getAutosaveMinutes() * 60 * 20;
        getServer().getScheduler().runTaskTimer(this, () -> guiManager.saveAllDirty(), autosaveInterval, autosaveInterval);

        // Backstop: force-closes any tracked session whose actor went offline without a clean
        // close. Bounded by the number of currently open sessions, not a database scan.
        long sweepInterval = (long) configManager.getOrphanSweepSeconds() * 20;
        getServer().getScheduler().runTaskTimer(this, () -> guiManager.sweepOrphans(), sweepInterval, sweepInterval);

        getServer().getServicesManager().register(VEnderChestApi.class,
                new VEnderChestApiImpl(sessionRegistry, storage), this, ServicePriority.Normal);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(configManager, storage).register();
            getLogger().info("PlaceholderAPI hook registered.");
        }

        getLogger().info("vEnderchest enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (guiManager != null) guiManager.closeAll(CloseReason.SHUTDOWN);
        if (migrationManager != null) migrationManager.close();
        if (storage != null) storage.close();
        getLogger().info("vEnderchest disabled.");
    }

    public void reload() {
        guiManager.closeAll(CloseReason.ADMIN_FORCE); // save + close open GUIs
        configManager.reload();                       // reload all YMLs
        auditLog.setLevel(parseAuditLevel(configManager.getVantidupeAuditLevel()));
        auditLog.setWarnConsoleOnConflict(configManager.isWarnConsoleOnConflict());
        transactionService.setBackupsEnabled(configManager.isBackupsEnabled());
        transactionService.setBackupsKeepPerVault(configManager.getBackupsKeepPerVault());
    }

    private static VaultAuditLog.Level parseAuditLevel(String raw) {
        try {
            return VaultAuditLog.Level.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return VaultAuditLog.Level.NORMAL;
        }
    }

    public static VEnderchest getInstance() { return instance; }
    public ConfigManager    getConfigManager()    { return configManager; }
    public GuiManager       getGuiManager()       { return guiManager; }
    public Storage          getStorage()          { return storage; }
    public MigrationManager getMigrationManager() { return migrationManager; }
}
