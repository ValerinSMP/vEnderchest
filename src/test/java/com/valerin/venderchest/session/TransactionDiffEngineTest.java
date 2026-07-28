package com.valerin.venderchest.session;

import com.valerin.venderchest.api.SlotAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link TransactionDiffEngine} using a Bukkit-free {@link ItemSnapshot} test
 * double, so the semantic classification (INSERT/REMOVE/REPLACE/MOVE) is verified without any
 * server dependency.
 */
class TransactionDiffEngineTest {

    // 9. Withdrawal of a non-stackable item -----------------------------------

    @Test
    void nonStackableItem_fullRemoval_isClassifiedAsRemove() {
        ItemSnapshot[] before = { Fake.of("DIAMOND_SWORD", 1) };
        ItemSnapshot[] after  = { null };

        List<SlotDiff> diffs = TransactionDiffEngine.diff(before, after);

        assertEquals(1, diffs.size());
        SlotDiff d = diffs.get(0);
        assertEquals(0, d.slot());
        assertEquals(SlotAction.REMOVE, d.action());
        assertEquals(1, d.amountBefore());
        assertEquals(0, d.amountAfter());
    }

    // 10. Partial withdrawal of a stack ---------------------------------------

    @Test
    void partialStackWithdrawal_isClassifiedAsReplaceWithAmountDelta() {
        ItemSnapshot[] before = { Fake.of("DIRT", 64) };
        ItemSnapshot[] after  = { Fake.of("DIRT", 32) };

        List<SlotDiff> diffs = TransactionDiffEngine.diff(before, after);

        assertEquals(1, diffs.size());
        SlotDiff d = diffs.get(0);
        assertEquals(SlotAction.REPLACE, d.action());
        assertEquals(64, d.amountBefore());
        assertEquals(32, d.amountAfter());
    }

    // 11. Normal deposit and withdraw -----------------------------------------

    @Test
    void depositAndWithdraw_ofUnrelatedItems_areClassifiedIndependently() {
        ItemSnapshot[] before = { Fake.of("STONE", 5), null };
        ItemSnapshot[] after  = { null, Fake.of("DIRT", 12) };

        List<SlotDiff> diffs = TransactionDiffEngine.diff(before, after);

        assertEquals(2, diffs.size());
        SlotDiff removedStone = diffs.stream().filter(d -> d.slot() == 0).findFirst().orElseThrow();
        SlotDiff insertedDirt = diffs.stream().filter(d -> d.slot() == 1).findFirst().orElseThrow();
        // Different kind and different amount - the move heuristic must not merge these.
        assertEquals(SlotAction.REMOVE, removedStone.action());
        assertEquals(SlotAction.INSERT, insertedDirt.action());
    }

    @Test
    void singleUnambiguousRelocate_isClassifiedAsMove() {
        ItemSnapshot[] before = { Fake.of("DIAMOND", 3), null };
        ItemSnapshot[] after  = { null, Fake.of("DIAMOND", 3) };

        List<SlotDiff> diffs = TransactionDiffEngine.diff(before, after);

        assertEquals(2, diffs.size());
        assertTrue(diffs.stream().allMatch(d -> d.action() == SlotAction.MOVE));
    }

    @Test
    void unchangedSlots_produceNoDiffEntries() {
        ItemSnapshot[] before = { Fake.of("STONE", 5), null };
        ItemSnapshot[] after  = { Fake.of("STONE", 5), null };

        assertTrue(TransactionDiffEngine.diff(before, after).isEmpty());
    }

    /** Bukkit-free {@link ItemSnapshot} test double. */
    private record Fake(String kind, int amount) implements ItemSnapshot {
        static ItemSnapshot of(String kind, int amount) { return new Fake(kind, amount); }

        @Override public boolean isEmpty() { return false; }

        @Override public boolean sameKind(ItemSnapshot other) {
            return other instanceof Fake f && f.kind.equals(kind);
        }

        @Override public int amount() { return amount; }
    }
}
