package com.valerin.venderchest.crossserver;

import java.util.Map;

public final class MutationRecovery {

    private MutationRecovery() {}

    public static RecoveryDecision decide(
            MutationState state, MutationPlan plan, Map<SlotRef, SlotValue> observed) {
        return switch (state) {
            case PREPARED -> {
                if (plan.matches(MutationPlan.Phase.BEFORE, observed)) {
                    yield RecoveryDecision.ABORT_WITHOUT_PLAYER_CHANGE;
                }
                if (plan.matches(MutationPlan.Phase.RESERVED, observed)) {
                    yield RecoveryDecision.RESTORE_BEFORE_THEN_ABORT;
                }
                yield RecoveryDecision.QUARANTINE;
            }
            case DB_COMMITTED -> {
                if (plan.matches(MutationPlan.Phase.AFTER, observed)) {
                    yield RecoveryDecision.ACK_WITHOUT_PLAYER_CHANGE;
                }
                if (plan.matches(MutationPlan.Phase.BEFORE, observed)
                        || plan.matches(MutationPlan.Phase.RESERVED, observed)) {
                    yield RecoveryDecision.APPLY_AFTER_THEN_ACK;
                }
                yield RecoveryDecision.QUARANTINE;
            }
            // Cursor payloads have a separate recovery protocol. Treating either one as a
            // v1 BEFORE/RESERVED/AFTER journal would invent item ownership.
            case CURSOR_STABLE, SETTLEMENT_PREPARED -> RecoveryDecision.QUARANTINE;
            case ACKED -> plan.matches(MutationPlan.Phase.AFTER, observed)
                    ? RecoveryDecision.ACK_WITHOUT_PLAYER_CHANGE : RecoveryDecision.QUARANTINE;
            case ABORTED -> plan.matches(MutationPlan.Phase.BEFORE, observed)
                    ? RecoveryDecision.ABORT_WITHOUT_PLAYER_CHANGE : RecoveryDecision.QUARANTINE;
            case QUARANTINED -> RecoveryDecision.QUARANTINE;
        };
    }
}
