package com.valerin.venderchest.session;

/**
 * Minimal, Bukkit-free view of "what's in a slot" needed to compute a semantic diff. An empty slot
 * is represented as {@code null}, matching the {@code ItemStack[]} convention used elsewhere in
 * this plugin — implementations of this interface are only ever non-empty items.
 */
public interface ItemSnapshot {

    boolean isEmpty();

    /** Same material/meta identity as {@code other} (ignoring amount) — mirrors {@code ItemStack#isSimilar}. */
    boolean sameKind(ItemSnapshot other);

    int amount();
}
