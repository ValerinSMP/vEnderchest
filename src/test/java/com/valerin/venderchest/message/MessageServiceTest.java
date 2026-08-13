package com.valerin.venderchest.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageServiceTest {

    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test
    void rendersConfiguredPrefixAndEmoji() {
        YamlConfiguration yaml = baseConfiguration();
        yaml.set("sample", "<prefix><emoji:success> <success>Listo");

        MessageService service = new MessageService(yaml);

        assertEquals("[vEC] ✔ Listo", plain.serialize(service.component("sample")));
    }

    @Test
    void defaultPrefixFollowsVisibleFormatContract() {
        YamlConfiguration yaml = defaultConfiguration();
        yaml.set("sample", "<prefix><emoji:success> <success>Listo");

        MessageService service = new MessageService(yaml);

        assertEquals("<dark_gray>[<primary>vEnderchest</primary>]</dark_gray> <reset>",
                yaml.getString("style.prefix"));
        assertEquals("[vEnderchest] ✔ Listo", plain.serialize(service.component("sample")));
    }

    @Test
    void preservesBlankLinesInMessageLists() {
        YamlConfiguration yaml = baseConfiguration();
        yaml.set("sample-lines", List.of("<primary>Inicio</primary>", "", "<muted>Fin</muted>"));

        MessageService service = new MessageService(yaml);
        List<Component> lines = service.lines("sample-lines");

        assertEquals(3, lines.size());
        assertEquals("Inicio", plain.serialize(lines.get(0)));
        assertEquals("", plain.serialize(lines.get(1)));
        assertEquals("Fin", plain.serialize(lines.get(2)));
    }

    @Test
    void parsesInteractiveDefaultHelpAndAboutMessages() {
        YamlConfiguration yaml = defaultConfiguration();
        MessageService service = new MessageService(yaml);

        service.lines("commands.help.header");
        service.lines("commands.help.user");
        service.lines("commands.help.admin");
        service.lines("commands.help.footer");
        service.lines("commands.about.lines", Placeholder.unparsed("version", "test"));
        service.component("commands.unknown", Placeholder.unparsed("command", "invalid"));
    }

    private YamlConfiguration defaultConfiguration() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("messages.yml")),
                StandardCharsets.UTF_8
        ));
    }

    private YamlConfiguration baseConfiguration() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("style.prefix", "<dark_gray>[</dark_gray><primary>vEC</primary><dark_gray>]</dark_gray> ");
        yaml.set("style.colors.primary", "#a970ff");
        yaml.set("style.colors.secondary", "#d6b8ff");
        yaml.set("style.colors.muted", "#8f8f8f");
        yaml.set("style.colors.success", "#64d98b");
        yaml.set("style.colors.warning", "#ffd166");
        yaml.set("style.colors.error", "#ff6b6b");
        yaml.set("style.emojis.success", "✔");
        return yaml;
    }
}
