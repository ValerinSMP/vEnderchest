package com.valerin.venderchest.command;

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

    private final GuiManager guiManager;
    private final Storage storage;
    private final ConfigManager config;

    public EcAdminCommand(GuiManager guiManager, Storage storage, ConfigManager config) {
        this.guiManager = guiManager;
        this.storage = storage;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("venderchest.admin")) {
            sender.sendMessage(config.msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> {
                config.reload();
                sender.sendMessage(config.msg("config-reloaded"));
                yield true;
            }
            case "view" -> handleView(sender, args);
            case "clear" -> handleClear(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleView(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(config.msg("players-only"));
            return true;
        }
        if (args.length < 2) { sendUsage(sender); return true; }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(config.msg("player-not-found",
                    Placeholder.unparsed("player", args[1])));
            return true;
        }

        int page = args.length >= 3 ? parsePageOrDefault(args[2], 1) : 1;
        clampPage(page);

        admin.sendMessage(config.msg("admin-viewing",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("page", String.valueOf(page))));
        guiManager.openPageAdmin(admin, target.getUniqueId(), page);
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

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(config.getMM().deserialize(
                "<gray>Uso: <white>/ecadmin <view|clear|reload> [jugador] [página]"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("view", "clear", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("view") || args[0].equalsIgnoreCase("clear"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3) {
            List<String> pages = new ArrayList<>();
            for (int i = 1; i <= config.getMaxPages(); i++) pages.add(String.valueOf(i));
            return pages.stream().filter(s -> s.startsWith(args[2])).toList();
        }
        return List.of();
    }
}
