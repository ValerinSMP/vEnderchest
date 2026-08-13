package com.valerin.venderchest.crossserver;

import java.util.Objects;
import java.util.UUID;

public record MutationJournalRecord(
        UUID mutationId,
        UUID ownerUuid,
        UUID actorUuid,
        UUID sessionId,
        long sequence,
        long fencingToken,
        int page,
        long baseRevision,
        Long newRevision,
        MutationState state,
        MutationPlan playerPlan,
        String vaultBefore,
        String vaultAfter
) {
    public MutationJournalRecord {
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(playerPlan, "playerPlan");
        Objects.requireNonNull(vaultBefore, "vaultBefore");
        Objects.requireNonNull(vaultAfter, "vaultAfter");
        if (sequence < 1 || fencingToken < 1 || page < 1 || baseRevision < 0) {
            throw new IllegalArgumentException("invalid mutation coordinates");
        }
    }
}
