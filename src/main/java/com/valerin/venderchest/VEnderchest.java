package com.valerin.venderchest;

import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.api.VEnderChestApi;
import com.valerin.venderchest.command.EcAdminCommand;
import com.valerin.venderchest.command.EcCommand;
import com.valerin.venderchest.command.VEnderchestCommand;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.crossserver.BukkitPlayerDataPort;
import com.valerin.venderchest.crossserver.CrossServerLifecycle;
import com.valerin.venderchest.crossserver.CrossServerMutationController;
import com.valerin.venderchest.crossserver.NetworkRuntimeFactory;
import com.valerin.venderchest.gui.GuiManager;
import com.valerin.venderchest.hook.PlaceholderHook;
import com.valerin.venderchest.listener.GuiListener;
import com.valerin.venderchest.listener.InterceptListener;
import com.valerin.venderchest.listener.PlayerJoinListener;
import com.valerin.venderchest.migration.MigrationManager;
import com.valerin.venderchest.migration.StorageMigrationCoordinator;
import com.valerin.venderchest.session.VEnderChestApiImpl;
import com.valerin.venderchest.session.VaultAuditLog;
import com.valerin.venderchest.session.VaultSessionRegistry;
import com.valerin.venderchest.session.VaultTransactionService;
import com.valerin.venderchest.storage.MysqlStorage;
import com.valerin.venderchest.storage.SqliteStorage;
import com.valerin.venderchest.storage.SqliteToMysqlMigration;
import com.valerin.venderchest.storage.Storage;
import com.valerin.venderchest.storage.StorageAccessGate;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VEnderchest extends JavaPlugin {

    private static VEnderchest instance;

    private ConfigManager        configManager;
    private Storage              storage;
    private GuiManager           guiManager;
    private MigrationManager     migrationManager;
    private VaultSessionRegistry sessionRegistry;
    private VaultAuditLog        auditLog;
    private VaultTransactionService transactionService;
    private CrossServerLifecycle crossServerLifecycle;
    private CrossServerMutationController crossServerController;
    private ExecutorService crossServerIo;
    private StorageAccessGate storageAccessGate;
    private StorageMigrationCoordinator storageMigration;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        long startedAt = System.nanoTime();
        instance = this;
        stopping = false;
        getLogger().info("Starting vEnderchest v" + getPluginMeta().getVersion() + "...");
        getLogger().info("Platform: Paper 1.21.11+ | Java 21 bytecode");

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
        storageAccessGate = new StorageAccessGate();
        auditLog = new VaultAuditLog(getLogger(), parseAuditLevel(configManager.getVantidupeAuditLevel()),
                configManager.isWarnConsoleOnConflict(), configManager.isAuditConsoleEnabled());
        transactionService = new VaultTransactionService(this, storage, sessionRegistry, auditLog,
                configManager.getVantidupeServerId(), configManager.isBackupsEnabled(), configManager.getBackupsKeepPerVault());

        guiManager = new GuiManager(this, storage, configManager, sessionRegistry, transactionService);
        guiManager.setStorageAccessGate(storageAccessGate);
        configureCrossServer();

        migrationManager = new MigrationManager(storage, getDataFolder(), configManager.getMaxPages(), getLogger());
        storageMigration = createStorageMigrationCoordinator();

        getServer().getPluginManager().registerEvents(new InterceptListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new GuiListener(guiManager, configManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(
                this, migrationManager,
                () -> !guiManager.isCrossServerModeRequired() && !storageAccessGate.isMaintenance(),
                storageAccessGate), this);

        var ecCmd = getCommand("ec");
        if (ecCmd != null) {
            var executor = new EcCommand(guiManager, configManager);
            ecCmd.setExecutor(executor);
            ecCmd.setTabCompleter(executor);
        }

        var ecAdminCmd = getCommand("venderchestadmin");
        if (ecAdminCmd != null) {
            var executor = new EcAdminCommand(this, guiManager, storage, configManager,
                    storageMigration, storageAccessGate);
            ecAdminCmd.setExecutor(executor);
            ecAdminCmd.setTabCompleter(executor);
        }

        var rootCmd = getCommand("venderchest");
        if (rootCmd != null) {
            var executor = new VEnderchestCommand(this, configManager.getMessageService());
            rootCmd.setExecutor(executor);
            rootCmd.setTabCompleter(executor);
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
                new VEnderChestApiImpl(sessionRegistry, storage, storageAccessGate), this, ServicePriority.Normal);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(configManager, storage).register();
            getLogger().info("PlaceholderAPI hook registered.");
        }

        getLogger().info("Storage: " + configManager.getDbType().toUpperCase());
        getLogger().info("Enabled successfully in " + elapsedMillis(startedAt) + " ms.");
    }

    @Override
    public void onDisable() {
        stopping = true;
        long startedAt = System.nanoTime();
        getLogger().info("Stopping vEnderchest...");
        getServer().getServicesManager().unregisterAll(this);
        if (storageMigration != null) storageMigration.close();
        if (guiManager != null) guiManager.closeAll(CloseReason.SHUTDOWN);
        if (crossServerController != null) crossServerController.shutdown();
        if (crossServerLifecycle != null) crossServerLifecycle.close();
        if (crossServerIo != null) crossServerIo.shutdownNow();
        if (migrationManager != null) migrationManager.close();
        if (storage != null) storage.close();
        getLogger().info("Disabled successfully in " + elapsedMillis(startedAt) + " ms.");
    }

    public void reload() {
        guiManager.closeAll(CloseReason.ADMIN_FORCE); // save + close open GUIs
        configManager.reload();                       // reload all YMLs
        reloadCrossServer();
        auditLog.setLevel(parseAuditLevel(configManager.getVantidupeAuditLevel()));
        auditLog.setWarnConsoleOnConflict(configManager.isWarnConsoleOnConflict());
        auditLog.setConsoleEnabled(configManager.isAuditConsoleEnabled());
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

    private void configureCrossServer() {
        boolean requested = configManager.isCrossServerRequested();
        guiManager.setCrossServerRequired(requested);
        var candidate = configManager.crossServerCandidate();
        if (!(storage instanceof MysqlStorage mysql)) {
            if (requested) getLogger().severe("Cross-server mode requires database.type=mysql; vault opens are blocked.");
            return;
        }
        if (!candidate.isValid()) {
            getLogger().severe("Cross-server configuration rejected: " + String.join("; ", candidate.errors()));
            return;
        }

        NetworkRuntimeFactory runtimes = new NetworkRuntimeFactory(mysql);
        crossServerLifecycle = new CrossServerLifecycle(runtimes);
        crossServerIo = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "vEnderchest-mutation-io");
            thread.setDaemon(true);
            return thread;
        });
        crossServerController = new CrossServerMutationController(
                crossServerLifecycle, runtimes, new BukkitPlayerDataPort(this), guiManager,
                crossServerIo, this::runCrossServerMain);
        guiManager.setCrossServerController(crossServerController, requested);
        crossServerLifecycle.start(candidate).whenComplete((result, error) -> {
            if (error != null || result == null || !result.accepted()) {
                getLogger().severe("Cross-server runtime did not start; vault opens remain blocked.");
            } else {
                getLogger().info("Cross-server mode: " + result.state());
            }
        });
    }

    private void reloadCrossServer() {
        boolean requested = configManager.isCrossServerRequested();
        var candidate = configManager.crossServerCandidate();
        if (crossServerLifecycle == null) {
            guiManager.setCrossServerRequired(requested);
            if (requested) {
                getLogger().warning("Cross-server mode cannot be enabled by reload without an active MySQL runtime; restart required.");
            }
            return;
        }
        if (!candidate.isValid()) {
            getLogger().warning("Cross-server reload rejected: " + String.join("; ", candidate.errors()));
            boolean active = crossServerLifecycle.activeSettings() != null
                    && crossServerLifecycle.activeSettings().enabled();
            guiManager.setCrossServerRequired(active);
            return;
        }

        guiManager.setCrossServerRequired(candidate.settings().enabled());
        crossServerLifecycle.reload(candidate).whenComplete((result, error) -> runCrossServerMain(() -> {
            if (error != null || result == null || !result.accepted()) {
                boolean active = crossServerLifecycle.activeSettings() != null
                        && crossServerLifecycle.activeSettings().enabled();
                guiManager.setCrossServerRequired(active);
                getLogger().warning("Cross-server reload rejected; the previous mode remains active.");
            } else {
                guiManager.setCrossServerRequired(result.state() == CrossServerLifecycle.State.ACTIVE);
                getLogger().info("Cross-server mode reloaded: " + result.state());
            }
        }));
    }

    private void runCrossServerMain(Runnable task) {
        if (stopping || !isEnabled()) return;
        if (getServer().isPrimaryThread()) {
            task.run();
        } else {
            getServer().getScheduler().runTask(this, () -> {
                if (!stopping && isEnabled()) task.run();
            });
        }
    }

    private StorageMigrationCoordinator createStorageMigrationCoordinator() {
        Path dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
        Path source = dataFolder.resolve(configManager.getSqliteFile()).normalize();
        SqliteToMysqlMigration migration = new SqliteToMysqlMigration(
                source, configManager.getTablePrefix(),
                SqliteToMysqlMigration.Destination.mysql(
                        configManager.getMysqlHost(), configManager.getMysqlPort(),
                        configManager.getMysqlDatabase(), configManager.getMysqlUsername(),
                        configManager.getMysqlPassword(), configManager.getTablePrefix()),
                dataFolder.resolve("storage-migration-checkpoint.json"));
        return new StorageMigrationCoordinator(migration, new StorageMigrationCoordinator.Environment() {
            @Override public void runMain(Runnable task) { runCrossServerMain(task); }
            @Override public boolean pluginEnabled() { return !stopping && isEnabled(); }
            @Override public boolean sourceActive() {
                return storage instanceof SqliteStorage && !storageAccessGate.isMaintenance();
            }
            @Override public String admissionRejection() {
                if (!(storage instanceof SqliteStorage)) {
                    return "start/resume exige database.type=sqlite activo.";
                }
                if (!getServer().getOnlinePlayers().isEmpty()) {
                    return "Debe haber cero jugadores online para iniciar la migración.";
                }
                if (guiManager.hasAnyOpenOrTrackedSession()) {
                    return "Hay sesiones o vistas de vault todavía activas.";
                }
                if (crossServerController != null && crossServerController.hasAnyTrackedOrInFlight()) {
                    return "Hay leases o mutaciones cross-server todavía activas.";
                }
                if (storageAccessGate.activeOperations() != 0) {
                    return "Hay operaciones de storage todavía activas; vuelve a intentar.";
                }
                return null;
            }
            @Override public boolean enterMaintenance() {
                return guiManager.enterStorageMaintenance();
            }
            @Override public void closeSourceStorage() {
                if (migrationManager != null) migrationManager.close();
                storage.close();
            }
        });
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public static VEnderchest getInstance() { return instance; }
    public ConfigManager    getConfigManager()    { return configManager; }
    public GuiManager       getGuiManager()       { return guiManager; }
    public Storage          getStorage()          { return storage; }
    public MigrationManager getMigrationManager() { return migrationManager; }
}
