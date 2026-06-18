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

    void close();
}
