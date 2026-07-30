package com.valerin.venderchest.command;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class VEnderchestCommand implements CommandExecutor, TabCompleter {

    private static final List<String> USER_SUBCOMMANDS = List.of("help", "about");

    private final VEnderchest plugin;
    private final MessageService messages;

    public VEnderchestCommand(VEnderchest plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "help", "ayuda", "?" -> {
                sendHelp(sender);
                yield true;
            }
            case "about", "info", "acerca" -> {
                messages.sendLines(sender, "commands.about.lines",
                        Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()));
                yield true;
            }
            default -> {
                messages.send(sender, "commands.unknown",
                        Placeholder.unparsed("command", subcommand));
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        messages.sendLines(sender, "commands.help.header");
        messages.sendLines(sender, "commands.help.user");
        if (sender.hasPermission("venderchest.admin")
                || sender.hasPermission("venderchest.admin.view")
                || sender.hasPermission("venderchest.admin.edit")) {
            messages.sendLines(sender, "commands.help.admin");
        }
        messages.sendLines(sender, "commands.help.footer");
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return USER_SUBCOMMANDS.stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
    }
}
