package com.valerin.venderchest.command;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.gui.GuiManager;
import com.valerin.venderchest.migration.StorageMigrationCoordinator;
import com.valerin.venderchest.storage.Storage;
import com.valerin.venderchest.storage.StorageAccessGate;
import com.valerin.venderchest.utils.RelativeTime;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class EcAdminCommand implements CommandExecutor, TabCompleter {

    private final VEnderchest plugin;
    private final GuiManager guiManager;
    private final Storage storage;
    private final ConfigManager config;
    private final StorageMigrationCoordinator storageMigration;
    private final StorageAccessGate storageGate;

    public EcAdminCommand(VEnderchest plugin, GuiManager guiManager, Storage storage,
                          ConfigManager config, StorageMigrationCoordinator storageMigration,
                          StorageAccessGate storageGate) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.storage = storage;
        this.config = config;
        this.storageMigration = storageMigration;
        this.storageGate = storageGate;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        // 'view' also accepts the granular permissions so staff can browse without full admin
        boolean isAdmin   = sender.hasPermission("venderchest.admin");
        boolean isViewer  = sender.hasPermission("venderchest.admin.view")
                         || sender.hasPermission("venderchest.admin.edit");
        if (!isAdmin && !(args[0].equalsIgnoreCase("view") && isViewer)) {
            sender.sendMessage(config.msg("no-permission"));
            return true;
        }
        String requested = args[0].toLowerCase();
        if (storageGate.isMaintenance()
                && !requested.equals("storage-migrate")
                && !requested.equals("help") && !requested.equals("ayuda")
                && !requested.equals("?") && !requested.equals("about")
                && !requested.equals("info") && !requested.equals("acerca")) {
            rejectMaintenance(sender);
            return true;
        }

        return switch (requested) {
            case "help", "ayuda", "?" -> {
                config.getMessageService().sendLines(sender, "commands.admin-help.lines");
                yield true;
            }
            case "about", "info", "acerca" -> {
                config.getMessageService().sendLines(sender, "commands.about.lines",
                        Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()));
                yield true;
            }
            case "reload" -> {
                if (rejectMaintenance(sender)) yield true;
                plugin.reload();
                sender.sendMessage(config.msg("config-reloaded"));
                yield true;
            }
            case "view"        -> handleView(sender, args);
            case "clear"       -> handleClear(sender, args);
            case "addvault"    -> handleVault(sender, args, true);
            case "removevault" -> handleVault(sender, args, false);
            case "migrate"     -> handleMigrate(sender, args);
            case "storage-migrate" -> handleStorageMigration(sender, args);
            case "restore"     -> handleRestore(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleView(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(config.msg("players-only"));
            return true;
        }
        if (args.length < 2) { sendUsage(sender); return true; }

        // Resolve offline player
        UUID   targetUuid;
        String targetName;
        Player online = Bukkit.getPlayer(args[1]);
        if (online != null) {
            targetUuid = online.getUniqueId();
            targetName = online.getName();
        } else {
            var op = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (op == null) {
                sender.sendMessage(config.msg("player-not-found",
                        Placeholder.unparsed("player", args[1])));
                return true;
            }
            targetUuid = op.getUniqueId();
            targetName = op.getName() != null ? op.getName() : args[1];
        }

        // edit permission → readOnly=false; view-only → readOnly=true
        boolean readOnly = !admin.hasPermission("venderchest.admin.edit");

        int page = clampPage(args.length >= 3 ? parsePageOrDefault(args[2], 1) : 1);
        admin.sendMessage(config.msg("admin-viewing",
                Placeholder.unparsed("player", targetName),
                Placeholder.unparsed("page", String.valueOf(page))));
        guiManager.openPageAdmin(admin, targetUuid, targetName, page, readOnly);
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (args.length < 2) { sendUsage(sender); return true; }
        if (guiManager.isCrossServerModeRequired()) {
            sender.sendMessage(config.msg("cross-server-admin-write-disabled"));
            return true;
        }

        // Support offline players via name lookup (online-only for simplicity)
        Player target = Bukkit.getPlayer(args[1]);
        UUID targetUuid;
        String targetName;

        if (target != null) {
            targetUuid = target.getUniqueId();
            targetName = target.getName();
        } else {
            // Try to get from usercache
            var offlinePlayer = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (offlinePlayer == null) {
                sender.sendMessage(config.msg("player-not-found",
                        Placeholder.unparsed("player", args[1])));
                return true;
            }
            targetUuid = offlinePlayer.getUniqueId();
            targetName = offlinePlayer.getName() != null ? offlinePlayer.getName() : args[1];
        }

        int page = args.length >= 3 ? parsePageOrDefault(args[2], 1) : 1;
        if (!storageGate.tryBegin()) return rejectMaintenance(sender);
        try {
            storage.clearPage(targetUuid, page);
            guiManager.invalidateCache(targetUuid, page); // direct DB write would otherwise leave cache stale
        } finally {
            storageGate.end();
        }
        sender.sendMessage(config.msg("admin-cleared",
                Placeholder.unparsed("player", targetName),
                Placeholder.unparsed("page", String.valueOf(page))));
        return true;
    }

    private int parsePageOrDefault(String s, int def) {
        try { return Math.max(1, Integer.parseInt(s)); }
        catch (NumberFormatException e) { return def; }
    }

    private int clampPage(int page) {
        return Math.max(1, Math.min(page, config.getMaxPages()));
    }

    private boolean handleVault(CommandSender sender, String[] args, boolean add) {
        if (args.length < 3) { sendUsage(sender); return true; }

        var offlinePlayer = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (offlinePlayer == null) {
            sender.sendMessage(config.msg("player-not-found",
                    Placeholder.unparsed("player", args[1])));
            return true;
        }

        int amount;
        try { amount = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) {
            sender.sendMessage(config.getMM().deserialize("<red>Cantidad inválida."));
            return true;
        }
        if (amount <= 0) {
            sender.sendMessage(config.getMM().deserialize("<red>La cantidad debe ser mayor a 0."));
            return true;
        }

        UUID uuid = offlinePlayer.getUniqueId();
        String name = offlinePlayer.getName() != null ? offlinePlayer.getName() : args[1];

        final boolean addFinal = add;
        runStorageAsync(sender, () -> {
            if (addFinal) storage.addExtraPages(uuid, amount);
            else          storage.removeExtraPages(uuid, amount);
            int newExtra = storage.getExtraPages(uuid);
            sender.sendMessage(config.getMM().deserialize(
                    (addFinal ? "<green>+" : "<red>-") + amount + " vault(s) " +
                    (addFinal ? "<green>añadido(s)" : "<red>removido(s)") +
                    " <gray>a <white>" + name + "<gray>. Extra total: <white>" + newExtra));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(config.getMM().deserialize(
                        (addFinal ? "<green>+" : "<red>-") + amount +
                        " vault(s) " + (addFinal ? "<green>añadido(s)" : "<red>removido(s)") +
                        " <gray>a tu enderchest."));
            }
        });
        return true;
    }

    private boolean handleMigrate(CommandSender sender, String[] args) {
        // /ecadmin migrate status <player>
        // /ecadmin migrate reset <player> <vanilla|axvaults>
        if (args.length < 2) {
            sender.sendMessage(config.getMM().deserialize(
                    "<gray>Uso: <white>/venderchestadmin migrate <status|reset> <jugador> [vanilla|axvaults]"));
            return true;
        }

        String sub = args[1].toLowerCase();

        if (sub.equals("status")) {
            if (args.length < 3) { sendUsage(sender); return true; }
            var op = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (op == null) {
                sender.sendMessage(config.msg("player-not-found", Placeholder.unparsed("player", args[2])));
                return true;
            }
            UUID uuid = op.getUniqueId();
            String name = op.getName() != null ? op.getName() : args[2];
            runStorageAsync(sender, () -> {
                boolean v = storage.isMigrated(uuid, "vanilla");
                boolean a = storage.isMigrated(uuid, "axvaults");
                sender.sendMessage(config.getMM().deserialize(
                        "<gray>Migración de <white>" + name + "<gray>:"
                        + " vanilla=<white>" + (v ? "<green>✔" : "<red>✘") + "<reset>"
                        + "<gray> axvaults=<white>" + (a ? "<green>✔" : "<red>✘")));
            });
            return true;
        }

        if (sub.equals("reset")) {
            if (args.length < 4) { sendUsage(sender); return true; }
            var op = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (op == null) {
                sender.sendMessage(config.msg("player-not-found", Placeholder.unparsed("player", args[2])));
                return true;
            }
            String type = args[3].toLowerCase();
            if (!type.equals("vanilla") && !type.equals("axvaults")) {
                sender.sendMessage(config.getMM().deserialize("<red>Tipo inválido. Usa <white>vanilla</white> o <white>axvaults</white>."));
                return true;
            }
            UUID resetUuid = op.getUniqueId();
            String name = op.getName() != null ? op.getName() : args[2];
            runStorageAsync(sender, () -> storage.unmarkMigrated(resetUuid, type));
            sender.sendMessage(config.getMM().deserialize(
                    "<green>Flag de migración <white>" + type + "</white> borrado para <white>" + name
                    + "<green>. Se re-migrará en el próximo login."));
            return true;
        }

        sendUsage(sender);
        return true;
    }

    /**
     * /ecadmin restore <player>                -> lists stored backups (newest first)
     * /ecadmin restore <player> <id>            -> read-only preview of that backup's content
     * /ecadmin restore <player> <id> confirm    -> actually restores it into the live page
     * <p>
     * Backups are written automatically on every real commit (see VaultTransactionService) -
     * this command only ever reads/restores them, never creates one directly.
     */
    /**
     * /ecadmin restore <player>                -> opens the clickable backup-list GUI (in-game),
     *                                              or a chat listing from console.
     * /ecadmin restore <player> <id>            -> opens the read-only preview GUI, with an
     *                                              in-GUI confirm-guarded restore button.
     * /ecadmin restore <player> <id> confirm    -> restores directly, no GUI - works from console.
     * <p>
     * Backups are written automatically on every real commit (see VaultTransactionService) -
     * this command only ever reads/restores them, never creates one directly.
     */
    private boolean handleRestore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(config.getMM().deserialize(
                    "<gray>Uso: <white>/venderchestadmin restore <jugador> [id] [confirm]"));
            return true;
        }

        var op = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (op == null) {
            sender.sendMessage(config.msg("player-not-found", Placeholder.unparsed("player", args[1])));
            return true;
        }
        UUID targetUuid = op.getUniqueId();
        String targetName = op.getName() != null ? op.getName() : args[1];

        if (args.length == 2) {
            if (sender instanceof Player admin) {
                guiManager.openBackupList(admin, targetUuid, targetName);
            } else {
                listBackupsInChat(sender, targetUuid, targetName);
            }
            return true;
        }

        int backupId;
        try {
            backupId = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(config.getMM().deserialize("<red>ID de backup inválido."));
            return true;
        }

        if (args.length >= 4 && args[3].equalsIgnoreCase("confirm")) {
            directRestoreById(sender, targetUuid, targetName, backupId);
            return true;
        }

        if (sender instanceof Player admin) {
            guiManager.openBackupPreviewDirect(admin, targetUuid, targetName, backupId);
        } else {
            sender.sendMessage(config.getMM().deserialize(
                    "<red>Previsualizar un backup requiere estar en el juego. Agregá <white>confirm</white> para restaurar directo."));
        }
        return true;
    }

    private void listBackupsInChat(CommandSender sender, UUID targetUuid, String targetName) {
        runStorageAsync(sender, () -> {
            List<Storage.BackupRecord> backups = storage.listBackups(targetUuid);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (backups.isEmpty()) {
                    sender.sendMessage(config.getMM().deserialize(
                            "<gray>No hay backups guardados para <white>" + targetName + "<gray>."));
                    return;
                }
                sender.sendMessage(config.getMM().deserialize(
                        "<gray>Backups de <white>" + targetName + "<gray> (más reciente primero):"));
                for (Storage.BackupRecord b : backups) {
                    sender.sendMessage(config.getMM().deserialize(
                            "<dark_gray>#<white>" + b.id() + " <dark_gray>página <white>" + b.page()
                                    + " <dark_gray>rev <white>" + b.revision()
                                    + " <dark_gray>· " + RelativeTime.since(b.createdAtMillis())));
                }
                sender.sendMessage(config.getMM().deserialize(
                        "<gray>Usa <white>/venderchestadmin restore " + targetName + " <id> confirm</white> para restaurar."));
            });
        });
    }

    /** Restores directly with no preview - the console-friendly path, also reachable from in-game. */
    private void directRestoreById(CommandSender sender, UUID targetUuid, String targetName, int backupId) {
        if (guiManager.isCrossServerModeRequired()) {
            sender.sendMessage(config.msg("cross-server-admin-write-disabled"));
            return;
        }
        runStorageAsync(sender, () -> {
            Storage.BackupRecord record = storage.getBackup(backupId);
            if (record == null || !record.uuid().equals(targetUuid)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(config.getMM().deserialize(
                        "<red>Ese backup no existe o no pertenece a " + targetName + ".")));
                return;
            }
            ItemStack[] items = storage.loadBackupItems(backupId);
            if (items == null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                        config.getMM().deserialize("<red>No se pudo leer el contenido del backup.")));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (guiManager.isVaultOpen(targetUuid, record.page())) {
                    sender.sendMessage(config.getMM().deserialize(
                            "<red>La página " + record.page() + " de " + targetName
                                    + " está abierta ahora mismo. Pedile que la cierre antes de restaurar."));
                    return;
                }
                runStorageAsync(sender, () -> {
                    storage.savePage(targetUuid, record.page(), items);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        guiManager.invalidateCache(targetUuid, record.page());
                        plugin.getLogger().warning("[vEnderchest] [audit] event=restore backup=" + backupId
                                + " owner=" + targetUuid + " vault=" + record.page() + " by=" + sender.getName());
                        sender.sendMessage(config.getMM().deserialize(
                                "<green>Backup <white>#" + backupId + "</white> restaurado en la página <white>"
                                        + record.page() + "</white> de <white>" + targetName + "</white>."));
                    });
                });
            });
        });
    }

    private boolean handleStorageMigration(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(config.getMM().deserialize(
                    "<gray>Uso: <white>/venderchestadmin storage-migrate <dry-run|start|resume|status>"));
            return true;
        }
        String action = args[1].toLowerCase();
        if ((action.equals("start") || action.equals("resume"))
                && !(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(config.getMM().deserialize(
                    "<red>start/resume solo se pueden ejecutar desde la consola."));
            return true;
        }

        if (action.equals("status")) {
            var status = storageMigration.status();
            sender.sendMessage(config.getMM().deserialize(
                    "<gray>Migración de storage: <white>" + status.status()
                            + "</white><gray>, procesadas=<white>" + status.processed()
                            + "</white>, insertadas=<white>" + status.inserted()
                            + "</white>, idénticas=<white>" + status.skipped()
                            + (status.lastKey() == null ? "" : "</white>, última=<white>" + status.lastKey())
                            + (status.conflict() == null ? "" : "</white>, conflicto=<red>" + status.conflict())));
            return true;
        }

        Consumer<StorageMigrationCoordinator.Result> report = result -> {
            sender.sendMessage(config.getMM().deserialize(
                    (result.success() ? "<green>" : "<red>") + result.message()));
            if (result.informational() || result.owners() != 0 || result.pages() != 0
                    || result.missing() != 0 || result.equal() != 0 || result.conflicts() != 0) {
                sender.sendMessage(config.getMM().deserialize(
                        "<gray>owners=<white>" + result.owners()
                                + "</white> pages=<white>" + result.pages()
                                + "</white> bytes=<white>" + result.bytes()
                                + "</white> missing=<white>" + result.missing()
                                + "</white> equal=<white>" + result.equal()
                                + "</white> conflicts=<white>" + result.conflicts()));
            }
            if (result.hash() != null) {
                sender.sendMessage(config.getMM().deserialize(
                        "<gray>SHA-256 lógico: <white>" + result.hash()));
            }
        };

        boolean accepted = switch (action) {
            case "dry-run" -> storageMigration.dryRun(report);
            case "start" -> storageMigration.start(report);
            case "resume" -> storageMigration.resume(report);
            default -> false;
        };
        if (!accepted) {
            sender.sendMessage(config.getMM().deserialize(
                    action.equals("dry-run") || action.equals("start") || action.equals("resume")
                            ? "<red>Ya hay una operación de migración ejecutándose."
                            : "<red>Acción inválida. Usa dry-run, start, resume o status."));
        } else {
            sender.sendMessage(config.getMM().deserialize(
                    "<gray>Operación iniciada; el resultado aparecerá en esta consola."));
        }
        return true;
    }

    private void runStorageAsync(CommandSender sender, Runnable task) {
        if (!storageGate.tryBegin()) {
            rejectMaintenance(sender);
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    task.run();
                } finally {
                    storageGate.end();
                }
            });
        } catch (RuntimeException error) {
            storageGate.end();
            throw error;
        }
    }

    private boolean rejectMaintenance(CommandSender sender) {
        sender.sendMessage(config.msg("storage-maintenance"));
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(config.getMM().deserialize(
                "<gray>Uso: <white>/venderchestadmin <view|clear|reload|addvault|removevault|migrate|storage-migrate|restore> [args]"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("help", "about", "view", "clear", "reload", "addvault", "removevault",
                            "migrate", "storage-migrate", "restore").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            return List.of("status", "reset").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("storage-migrate")) {
            return List.of("dry-run", "start", "resume", "status").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("migrate") && args[1].equalsIgnoreCase("reset")) {
            return List.of("vanilla", "axvaults").stream()
                    .filter(s -> s.startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("restore")) {
            return List.of("confirm").stream().filter(s -> s.startsWith(args[3].toLowerCase())).toList();
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3 && (sub.equals("view") || sub.equals("clear"))) {
            List<String> pages = new ArrayList<>();
            for (int i = 1; i <= config.getMaxPages(); i++) pages.add(String.valueOf(i));
            return pages.stream().filter(s -> s.startsWith(args[2])).toList();
        }
        if (args.length == 3 && (sub.equals("addvault") || sub.equals("removevault"))) {
            return List.of("1", "2", "3", "5").stream()
                    .filter(s -> s.startsWith(args[2])).toList();
        }
        return List.of();
    }
}
