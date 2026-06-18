package com.valerin.venderchest.hook;

import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.storage.Storage;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlaceholderHook extends PlaceholderExpansion {

    private final ConfigManager config;
    private final Storage storage;

    public PlaceholderHook(ConfigManager config, Storage storage) {
        this.config = config;
        this.storage = storage;
    }

    @Override public @NotNull String getIdentifier() { return "venderchest"; }
    @Override public @NotNull String getAuthor() { return "Valerin"; }
    @Override public @NotNull String getVersion() { return "1.0.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        // %venderchest_pages_<player>%   — max pages available
        if (params.startsWith("pages_")) {
            String name = params.substring(6);
            if (player.getName() != null && player.getName().equalsIgnoreCase(name)) {
                Player online = player.getPlayer();
                if (online != null) return String.valueOf(config.getMaxPages(online));
            }
            return String.valueOf(config.getDefaultPages());
        }

        // %venderchest_used_<player>%    — count of non-empty pages
        if (params.startsWith("used_")) {
            return String.valueOf(storage.countUsedPages(player.getUniqueId()));
        }

        return null;
    }
}
