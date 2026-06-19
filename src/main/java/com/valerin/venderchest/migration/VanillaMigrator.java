package com.valerin.venderchest.migration;

import com.valerin.venderchest.storage.Storage;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class VanillaMigrator {

    private static final String TYPE = "vanilla";

    private final Storage storage;
    private final int     maxPages;
    private final Logger  log;

    public VanillaMigrator(Storage storage, int maxPages, Logger log) {
        this.storage  = storage;
        this.maxPages = maxPages;
        this.log      = log;
    }

    /** @param vanillaItems captured on main thread before this async call */
    public void migrate(UUID uuid, ItemStack[] vanillaItems) {
        if (storage.isMigrated(uuid, TYPE)) return;

        ItemStack[] vanilla = vanillaItems;

        List<ItemStack> existingShulkers = new ArrayList<>();
        List<ItemStack> regularItems     = new ArrayList<>();

        for (ItemStack item : vanilla) {
            if (item == null || item.getType().isAir()) continue;
            if (item.getType().name().endsWith("SHULKER_BOX")) {
                existingShulkers.add(item);
            } else {
                regularItems.add(item);
            }
        }

        if (existingShulkers.isEmpty() && regularItems.isEmpty()) {
            storage.markMigrated(uuid, TYPE);
            return;
        }

        List<ItemStack> packedBoxes = ShulkerBoxHelper.packAll(regularItems);

        List<ItemStack> toPlace = new ArrayList<>(existingShulkers);
        toPlace.addAll(packedBoxes);

        int lost = ShulkerBoxHelper.placeItems(uuid, toPlace, storage, maxPages, log);
        if (lost > 0) {
            log.warning("[vEnderchest] vanilla: " + uuid + ": " + lost
                    + " shulker box(es) couldn't be placed (all pages full).");
        } else {
            int total = existingShulkers.size() + regularItems.size();
            log.info("[vEnderchest] vanilla: " + uuid + ": " + total + " item(s) → "
                    + toPlace.size() + " shulker box(es) placed.");
        }

        storage.markMigrated(uuid, TYPE);
    }
}
