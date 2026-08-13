package com.valerin.venderchest.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerSettingsTest {

    @Test
    void disabledSingleServerAcceptsSqliteWithoutRedis() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("database.type", "sqlite");
        yaml.set("database.table-prefix", "solo_");

        var validation = CrossServerSettings.parse(yaml);

        assertTrue(validation.isValid());
        assertFalse(validation.settings().enabled());
        assertEquals("solo_", validation.settings().tablePrefix());
    }

    @Test
    void enabledModeRequiresMysqlAndSafeNamespace() {
        YamlConfiguration yaml = enabled();
        yaml.set("database.type", "sqlite");
        yaml.set("cross-server.network", "bad namespace");

        var validation = CrossServerSettings.parse(yaml);

        assertFalse(validation.isValid());
        assertEquals(2, validation.errors().size());
    }

    @Test
    void credentialsNeverAppearInDiagnostics() {
        YamlConfiguration yaml = enabled();
        yaml.set("cross-server.redis.username", "acl-user");
        yaml.set("cross-server.redis.password", "super-secret");

        var settings = CrossServerSettings.parse(yaml).settings();

        assertFalse(settings.toString().contains("super-secret"));
        assertFalse(settings.redis().toString().contains("acl-user"));
        assertTrue(settings.redis().toString().contains("<redacted>"));
    }

    @Test
    void unsafeLeaseWindowIsRejected() {
        YamlConfiguration yaml = enabled();
        yaml.set("cross-server.lease.renew-ms", 25_000);

        assertFalse(CrossServerSettings.parse(yaml).isValid());
    }

    private YamlConfiguration enabled() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("database.type", "mysql");
        yaml.set("database.table-prefix", "ec_");
        yaml.set("cross-server.enabled", true);
        yaml.set("cross-server.network", "valerin");
        yaml.set("cross-server.server-id", "survival-1");
        return yaml;
    }
}
