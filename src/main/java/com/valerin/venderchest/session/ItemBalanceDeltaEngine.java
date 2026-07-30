package com.valerin.venderchest.session;

import java.util.ArrayList;
import java.util.List;

/** Computes net vault/player transfers while ignoring moves between slots inside the vault. */
public final class ItemBalanceDeltaEngine {
    private ItemBalanceDeltaEngine() {
    }

    public static List<ItemBalanceDelta> between(ItemSnapshot[] before, ItemSnapshot[] after) {
        List<ItemSnapshot> kinds = new ArrayList<>();
        List<ItemBalanceDelta> deltas = new ArrayList<>();

        collectKinds(before, before, after, kinds, deltas);
        collectKinds(after, before, after, kinds, deltas);
        return List.copyOf(deltas);
    }

    private static void collectKinds(ItemSnapshot[] candidates, ItemSnapshot[] before, ItemSnapshot[] after,
                                     List<ItemSnapshot> kinds, List<ItemBalanceDelta> deltas) {
        for (ItemSnapshot item : candidates) {
            if (empty(item) || kinds.stream().anyMatch(item::sameKind)) {
                continue;
            }
            kinds.add(item);
            int beforeAmount = total(before, item);
            int afterAmount = total(after, item);
            int delta = afterAmount - beforeAmount;
            if (delta == 0) {
                continue;
            }

            boolean gainedByVault = delta > 0;
            ItemSnapshot[] source = gainedByVault ? after : before;
            int sourceSlot = find(source, item);
            deltas.add(new ItemBalanceDelta(gainedByVault, sourceSlot, Math.abs(delta)));
        }
    }

    private static int total(ItemSnapshot[] items, ItemSnapshot kind) {
        int total = 0;
        for (ItemSnapshot item : items) {
            if (!empty(item) && kind.sameKind(item)) {
                total += item.amount();
            }
        }
        return total;
    }

    private static int find(ItemSnapshot[] items, ItemSnapshot kind) {
        for (int i = 0; i < items.length; i++) {
            if (!empty(items[i]) && kind.sameKind(items[i])) {
                return i;
            }
        }
        throw new IllegalStateException("Representative item disappeared");
    }

    private static boolean empty(ItemSnapshot item) {
        return item == null || item.isEmpty();
    }
}
