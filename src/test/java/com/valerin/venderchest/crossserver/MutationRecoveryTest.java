package com.valerin.venderchest.crossserver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutationRecoveryTest {

    private static final SlotRef SLOT = new SlotRef(SlotRef.Area.PLAYER, 4);
    private static final SlotValue EMPTY = SlotValue.empty();
    private static final SlotValue ITEM = SlotValue.fromText("unique-item");

    @Test
    void insertedItemIsReservedBeforeCommitAndRecoveredDeterministically() {
        MutationPlan insert = plan(ITEM, EMPTY, EMPTY);

        assertEquals(RecoveryDecision.ABORT_WITHOUT_PLAYER_CHANGE,
                MutationRecovery.decide(MutationState.PREPARED, insert, observed(ITEM)));
        assertEquals(RecoveryDecision.RESTORE_BEFORE_THEN_ABORT,
                MutationRecovery.decide(MutationState.PREPARED, insert, observed(EMPTY)));
        assertEquals(RecoveryDecision.APPLY_AFTER_THEN_ACK,
                MutationRecovery.decide(MutationState.DB_COMMITTED, insert, observed(ITEM)));
        assertEquals(RecoveryDecision.ACK_WITHOUT_PLAYER_CHANGE,
                MutationRecovery.decide(MutationState.DB_COMMITTED, insert, observed(EMPTY)));
    }

    @Test
    void withdrawnItemIsDeliveredOnlyAfterDatabaseCommit() {
        MutationPlan withdraw = plan(EMPTY, EMPTY, ITEM);

        assertEquals(RecoveryDecision.ABORT_WITHOUT_PLAYER_CHANGE,
                MutationRecovery.decide(MutationState.PREPARED, withdraw, observed(EMPTY)));
        assertEquals(RecoveryDecision.APPLY_AFTER_THEN_ACK,
                MutationRecovery.decide(MutationState.DB_COMMITTED, withdraw, observed(EMPTY)));
        assertEquals(RecoveryDecision.ACK_WITHOUT_PLAYER_CHANGE,
                MutationRecovery.decide(MutationState.DB_COMMITTED, withdraw, observed(ITEM)));
    }

    @Test
    void shiftClickOrDragDivergenceQuarantinesWithoutPartialCompensation() {
        SlotRef second = new SlotRef(SlotRef.Area.PLAYER, 7);
        MutationPlan mixed = new MutationPlan(List.of(
                new SlotMutation(SLOT, ITEM, EMPTY, EMPTY),
                new SlotMutation(second, EMPTY, EMPTY, SlotValue.fromText("withdrawn"))
        ));

        assertEquals(RecoveryDecision.QUARANTINE, MutationRecovery.decide(
                MutationState.DB_COMMITTED, mixed,
                Map.of(SLOT, SlotValue.fromText("third-party-change"), second, EMPTY)));
    }

    private static MutationPlan plan(SlotValue before, SlotValue reserved, SlotValue after) {
        return new MutationPlan(List.of(new SlotMutation(SLOT, before, reserved, after)));
    }

    private static Map<SlotRef, SlotValue> observed(SlotValue value) {
        return Map.of(SLOT, value);
    }
}
