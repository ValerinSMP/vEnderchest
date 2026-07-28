package com.valerin.venderchest.api;

import org.bukkit.inventory.ItemStack;

/**
 * One slot of a {@link VaultSnapshot}. Immutable: {@code item} is defensively cloned both on
 * construction and on every read, so neither mutating a returned instance nor mutating the
 * plugin's internal item can ever affect the other.
 */
public record VaultSlot(int slot, ItemStack item, String locationKey) {

    public VaultSlot {
        item = item != null ? item.clone() : null;
    }

    @Override
    public ItemStack item() {
        return item != null ? item.clone() : null;
    }
}
