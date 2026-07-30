package com.valerin.venderchest.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Objects;

public final class MessageService {

    private static final String MISSING_MESSAGE = "<error>Missing message: <key>";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private FileConfiguration messages;

    public MessageService(FileConfiguration messages) {
        reload(messages);
    }

    public void reload(FileConfiguration messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public Component component(String key, TagResolver... resolvers) {
        return deserialize(messages.getString(key, MISSING_MESSAGE), resolvers);
    }

    public Component componentWithoutPrefix(String key, TagResolver... resolvers) {
        String raw = messages.getString(key, MISSING_MESSAGE);
        return miniMessage.deserialize(raw, combinedResolver(false, resolvers));
    }

    public List<Component> lines(String key, TagResolver... resolvers) {
        if (!messages.isList(key)) {
            return List.of(component(key, resolvers));
        }
        return messages.getStringList(key).stream()
                .map(line -> line.isEmpty() ? Component.empty() : deserialize(line, resolvers))
                .toList();
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(component(key, resolvers));
    }

    public void sendLines(CommandSender sender, String key, TagResolver... resolvers) {
        lines(key, resolvers).forEach(sender::sendMessage);
    }

    public MiniMessage miniMessage() {
        return miniMessage;
    }

    private Component deserialize(String raw, TagResolver... resolvers) {
        return miniMessage.deserialize(raw, combinedResolver(true, resolvers));
    }

    private TagResolver combinedResolver(boolean includePrefix, TagResolver... resolvers) {
        TagResolver style = styleResolver();
        TagResolver.Builder builder = TagResolver.builder().resolver(style);
        if (includePrefix) {
            Component prefix = miniMessage.deserialize(
                    messages.getString("style.prefix", ""),
                    style
            );
            builder.resolver(TagResolver.resolver("prefix", Tag.inserting(prefix)));
        }
        if (resolvers.length > 0) {
            builder.resolver(TagResolver.resolver(resolvers));
        }
        return builder.build();
    }

    private TagResolver styleResolver() {
        return TagResolver.builder()
                .resolver(colorTag("primary", "style.colors.primary", "#a970ff"))
                .resolver(colorTag("secondary", "style.colors.secondary", "#d6b8ff"))
                .resolver(colorTag("muted", "style.colors.muted", "#8f8f8f"))
                .resolver(colorTag("success", "style.colors.success", "#64d98b"))
                .resolver(colorTag("warning", "style.colors.warning", "#ffd166"))
                .resolver(colorTag("error", "style.colors.error", "#ff6b6b"))
                .resolver(TagResolver.resolver("emoji", (arguments, context) -> {
                    String key = arguments.popOr("Expected an emoji key").value();
                    String value = messages.getString("style.emojis." + key, "[" + key + "]");
                    return Tag.inserting(Component.text(value));
                }))
                .build();
    }

    private TagResolver colorTag(String tag, String path, String fallback) {
        String configured = messages.getString(path, fallback);
        TextColor color = TextColor.fromHexString(configured);
        if (color == null) {
            color = TextColor.fromHexString(fallback);
        }
        return TagResolver.resolver(tag, Tag.styling(Objects.requireNonNull(color)));
    }
}
