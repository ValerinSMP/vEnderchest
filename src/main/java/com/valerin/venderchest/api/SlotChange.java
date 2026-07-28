package com.valerin.venderchest.api;

import org.bukkit.inventory.ItemStack;

/**
 * One slot's before/after state within a committed transaction. Immutable: {@code before}/{@code after}
 * are defensively cloned both on construction and on every read.
 */
public record SlotChange(int slot, SlotAction action, ItemStack before, ItemStack after,
                          int amountBefore, int amountAfter, String locationKey) {

    public SlotChange {
        before = before != null ? before.clone() : null;
        after = after != null ? after.clone() : null;
    }

    @Override
    public ItemStack before() {
        return before != null ? before.clone() : null;
    }

    @Override
    public ItemStack after() {
        return after != null ? after.clone() : null;
    }
}
