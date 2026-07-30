package com.valerin.venderchest.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemBalanceDeltaEngineTest {
    @Test
    void movingInsideVaultNeedsNoPlayerRollback() {
        ItemSnapshot[] before = {Fake.of("PICKAXE", 1), null};
        ItemSnapshot[] after = {null, Fake.of("PICKAXE", 1)};

        assertTrue(ItemBalanceDeltaEngine.between(before, after).isEmpty());
    }

    @Test
    void withdrawalMustBeRemovedFromPlayerWhenCommitConflicts() {
        List<ItemBalanceDelta> deltas = ItemBalanceDeltaEngine.between(
                new ItemSnapshot[]{Fake.of("HALLOWEEN_PICKAXE", 1)},
                new ItemSnapshot[]{null});

        assertEquals(List.of(new ItemBalanceDelta(false, 0, 1)), deltas);
    }

    @Test
    void rejectedDepositMustBeReturnedToPlayer() {
        List<ItemBalanceDelta> deltas = ItemBalanceDeltaEngine.between(
                new ItemSnapshot[]{null},
                new ItemSnapshot[]{Fake.of("HALLOWEEN_PICKAXE", 1)});

        assertEquals(List.of(new ItemBalanceDelta(true, 0, 1)), deltas);
    }

    @Test
    void aggregatesStacksOfTheSameKind() {
        List<ItemBalanceDelta> deltas = ItemBalanceDeltaEngine.between(
                new ItemSnapshot[]{Fake.of("DIAMOND", 32), Fake.of("DIAMOND", 32)},
                new ItemSnapshot[]{Fake.of("DIAMOND", 10), null});

        assertEquals(List.of(new ItemBalanceDelta(false, 0, 54)), deltas);
    }

    private record Fake(String kind, int amount) implements ItemSnapshot {
        static ItemSnapshot of(String kind, int amount) {
            return new Fake(kind, amount);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean sameKind(ItemSnapshot other) {
            return other instanceof Fake fake && kind.equals(fake.kind);
        }
    }
}
