package com.valerin.venderchest.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerMergeTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadAddsMissingDefaultsWithoutRewritingExistingConfiguration() throws Exception {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, """
                # comentario personalizado
                audit:
                  console-enabled: false
                gui:
                  existing-slots: []
                custom:
                  untouched: kept
                """);

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("audit.console-enabled", true);
        defaults.set("audit.level", "NORMAL");
        defaults.set("gui.new-slots", List.of(1, 2, 3));
        defaults.set("gui.existing-slots", List.of(9));

        YamlConfiguration firstLoad = ConfigManager.loadMerged(file.toFile(), defaults);
        assertEquals(false, firstLoad.getBoolean("audit.console-enabled"));
        assertEquals("NORMAL", firstLoad.getString("audit.level"));
        assertEquals(List.of(1, 2, 3), firstLoad.getIntegerList("gui.new-slots"));
        assertTrue(firstLoad.getIntegerList("gui.existing-slots").isEmpty());
        assertEquals("kept", firstLoad.getString("custom.untouched"));
        assertTrue(Files.readString(file).contains("# comentario personalizado"));

        byte[] afterFirstLoad = Files.readAllBytes(file);
        var modifiedAfterFirstLoad = Files.getLastModifiedTime(file);
        YamlConfiguration secondLoad = ConfigManager.loadMerged(file.toFile(), defaults);

        assertArrayEquals(afterFirstLoad, Files.readAllBytes(file));
        assertEquals(modifiedAfterFirstLoad, Files.getLastModifiedTime(file));
        assertEquals("NORMAL", secondLoad.getString("audit.level"));
    }
}
