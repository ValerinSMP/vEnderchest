package com.valerin.venderchest.session;

import com.valerin.venderchest.api.SlotAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure slot-by-slot diff of a vault's before/after content. No Bukkit dependency, so it is
 * exercised directly by unit tests without a running server.
 */
public final class TransactionDiffEngine {

    private TransactionDiffEngine() {}

    public static List<SlotDiff> diff(ItemSnapshot[] before, ItemSnapshot[] after) {
        int size = Math.max(before.length, after.length);
        List<SlotDiff> diffs = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemSnapshot b = i < before.length ? before[i] : null;
            ItemSnapshot a = i < after.length ? after[i] : null;
            boolean bEmpty = isEmpty(b);
            boolean aEmpty = isEmpty(a);

            if (bEmpty && aEmpty) continue;
            if (bEmpty) {
                diffs.add(new SlotDiff(i, SlotAction.INSERT, 0, a.amount()));
            } else if (aEmpty) {
                diffs.add(new SlotDiff(i, SlotAction.REMOVE, b.amount(), 0));
            } else if (b.sameKind(a) && b.amount() == a.amount()) {
                // unchanged - no diff entry
            } else {
                diffs.add(new SlotDiff(i, SlotAction.REPLACE, b.amount(), a.amount()));
            }
        }
        return reclassifyMoves(diffs, before, after);
    }

    /**
     * Heuristic, best-effort forensic hint: if there is exactly one REMOVE and exactly one INSERT
     * whose items are of the same kind and carry the same amount, re-tag both as MOVE. Ambiguous
     * multi-item shuffles are deliberately left as separate INSERT/REMOVE entries rather than
     * guessed at.
     */
    private static List<SlotDiff> reclassifyMoves(List<SlotDiff> diffs, ItemSnapshot[] before, ItemSnapshot[] after) {
        List<SlotDiff> removes = diffs.stream().filter(d -> d.action() == SlotAction.REMOVE).toList();
        List<SlotDiff> inserts = diffs.stream().filter(d -> d.action() == SlotAction.INSERT).toList();
        if (removes.size() != 1 || inserts.size() != 1) return diffs;

        SlotDiff removed = removes.get(0);
        SlotDiff inserted = inserts.get(0);
        ItemSnapshot removedItem = before[removed.slot()];
        ItemSnapshot insertedItem = after[inserted.slot()];
        if (removedItem == null || insertedItem == null) return diffs;
        if (!removedItem.sameKind(insertedItem) || removed.amountBefore() != inserted.amountAfter()) return diffs;

        List<SlotDiff> result = new ArrayList<>(diffs);
        result.replaceAll(d -> {
            if (d == removed || d == inserted) return new SlotDiff(d.slot(), SlotAction.MOVE, d.amountBefore(), d.amountAfter());
            return d;
        });
        return result;
    }

    private static boolean isEmpty(ItemSnapshot s) {
        return s == null || s.isEmpty();
    }
}
