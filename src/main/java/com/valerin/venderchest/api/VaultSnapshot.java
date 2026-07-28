package com.valerin.venderchest.api;

import java.util.List;
import java.util.UUID;

/** A read-only, point-in-time snapshot of one vault's persisted content and revision. */
public record VaultSnapshot(UUID ownerUuid, String vaultId, long revision, List<VaultSlot> slots) {

    public VaultSnapshot {
        slots = List.copyOf(slots);
    }
}
