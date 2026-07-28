package com.valerin.venderchest.session;

import org.bukkit.inventory.ItemStack;

/** Adapts a real Bukkit {@link ItemStack} to the Bukkit-free {@link ItemSnapshot} contract. */
public final class BukkitItemSnapshot implements ItemSnapshot {

    private final ItemStack item;

    private BukkitItemSnapshot(ItemStack item) {
        this.item = item;
    }

    /** Returns null for an empty/air slot, matching the {@code ItemStack[]} convention used elsewhere. */
    public static ItemSnapshot of(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return new BukkitItemSnapshot(item);
    }

    @Override
    public boolean isEmpty() { return false; }

    @Override
    public boolean sameKind(ItemSnapshot other) {
        return other instanceof BukkitItemSnapshot bis && item.isSimilar(bis.item);
    }

    @Override
    public int amount() { return item.getAmount(); }
}
