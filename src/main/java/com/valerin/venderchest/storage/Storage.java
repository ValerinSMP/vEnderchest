package com.valerin.venderchest.storage;

import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.UUID;

public interface Storage {

    void init() throws SQLException;

    /** Returns array[45]; null slots = empty. */
    ItemStack[] loadPage(UUID uuid, int page);

    void savePage(UUID uuid, int page, ItemStack[] items);

    void clearPage(UUID uuid, int page);

    /** Count of pages that have at least one item. */
    int countUsedPages(UUID uuid);

    /** Returns page → non-empty slot count for all saved pages of this player. */
    java.util.Map<Integer, Integer> countPageItems(UUID uuid);

    // ── Extra vaults (purchased separately, stacks on top of VIP permission) ──

    int getExtraPages(UUID uuid);

    void addExtraPages(UUID uuid, int amount);

    /** Removes amount, floors at 0. */
    void removeExtraPages(UUID uuid, int amount);

    void setExtraPages(UUID uuid, int amount);

    // ── Migration tracking ──

    boolean isMigrated(UUID uuid, String type);

    void markMigrated(UUID uuid, String type);

    void unmarkMigrated(UUID uuid, String type);

    void close();
}
