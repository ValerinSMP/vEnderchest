package com.valerin.venderchest.command;

import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.gui.GuiManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class EcCommand implements CommandExecutor, TabCompleter {

    private final GuiManager guiManager;
    private final ConfigManager config;

    public EcCommand(GuiManager guiManager, ConfigManager config) {
        this.guiManager = guiManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.msg("players-only"));
            return true;
        }

        if (!player.hasPermission("venderchest.use")) {
            player.sendMessage(config.msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            guiManager.openMainMenu(player);
            return true;
        }

        int page;
        try {
            page = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(config.msg("page-out-of-range",
                    Placeholder.unparsed("page", args[0]),
                    Placeholder.unparsed("max", String.valueOf(config.getMaxPages(player)))));
            return true;
        }

        int maxPages = config.getMaxPages(player);
        if (page < 1 || page > config.getMaxPages()) {
            player.sendMessage(config.msg("page-out-of-range",
                    Placeholder.unparsed("page", String.valueOf(page)),
                    Placeholder.unparsed("max", String.valueOf(maxPages))));
            return true;
        }

        if (page > maxPages) {
            player.sendMessage(config.msg("page-locked",
                    Placeholder.unparsed("page", String.valueOf(page))));
            return true;
        }

        guiManager.openPage(player, page);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            int max = config.getMaxPages(player);
            List<String> opts = new java.util.ArrayList<>();
            for (int i = 1; i <= max; i++) opts.add(String.valueOf(i));
            return opts.stream().filter(s -> s.startsWith(args[0])).toList();
        }
        return List.of();
    }
}
