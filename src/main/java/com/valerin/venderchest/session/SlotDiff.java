package com.valerin.venderchest.session;

import com.valerin.venderchest.api.SlotAction;

/** Pure result of diffing one slot's before/after state. No Bukkit dependency. */
public record SlotDiff(int slot, SlotAction action, int amountBefore, int amountAfter) {
}
