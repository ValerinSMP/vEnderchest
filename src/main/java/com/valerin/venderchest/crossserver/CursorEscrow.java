package com.valerin.venderchest.crossserver;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable owner of a cross-server carried stack. The Bukkit cursor is only its projection. */
public record CursorEscrow(
        UUID escrowId,
        long opSequence,
        SlotValue canonical,
        SlotValue projection,
        List<SlotMutation> fallback
) {
    public CursorEscrow {
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(canonical, "canonical");
        Objects.requireNonNull(projection, "projection");
        fallback = List.copyOf(fallback);
        if (opSequence < 1 || canonical.bytes().length == 0 || projection.bytes().length == 0) {
            throw new IllegalArgumentException("invalid cursor escrow");
        }
    }
}
