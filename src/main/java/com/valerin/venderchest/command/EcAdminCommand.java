package com.valerin.venderchest.command;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.gui.GuiManager;
import com.valerin.venderchest.storage.Storage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EcAdminCommand implements CommandExecutor, TabCompleter {

    private final VEnderchest plugin;
    private final GuiManager guiManager;
    private final Storage storage;
    private final ConfigManager config;

    public EcAdminCommand(VEnderchest plugin, GuiManager guiManager, Storage storage, ConfigManager config) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.storage = storage;
        this.config = config;
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

        return switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(config.msg("config-reloaded"));
                yield true;
            }
            case "view"        -> handleView(sender, args);
            case "clear"       -> handleClear(sender, args);
            case "addvault"    -> handleVault(sender, args, true);
            case "removevault" -> handleVault(sender, args, false);
            case "migrate"     -> handleMigrate(sender, args);
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
        storage.clearPage(targetUuid, page);
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
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
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
                    "<gray>Uso: <white>/ecadmin migrate <status|reset> <jugador> [vanilla|axvaults]"));
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
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
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
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> storage.unmarkMigrated(resetUuid, type));
            sender.sendMessage(config.getMM().deserialize(
                    "<green>Flag de migración <white>" + type + "</white> borrado para <white>" + name
                    + "<green>. Se re-migrará en el próximo login."));
            return true;
        }

        sendUsage(sender);
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(config.getMM().deserialize(
                "<gray>Uso: <white>/ecadmin <view|clear|reload|addvault|removevault|migrate> [jugador] [args]"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("view", "clear", "reload", "addvault", "removevault", "migrate").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            return List.of("status", "reset").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("migrate") && args[1].equalsIgnoreCase("reset")) {
            return List.of("vanilla", "axvaults").stream()
                    .filter(s -> s.startsWith(args[3].toLowerCase())).toList();
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
