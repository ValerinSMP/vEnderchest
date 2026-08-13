package com.valerin.venderchest.crossserver;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record MutationPlan(
        int schemaVersion,
        List<SlotMutation> playerSlots,
        CursorEscrow escrow,
        CursorSettlement settlement
) {

    public MutationPlan(List<SlotMutation> playerSlots) {
        this(1, playerSlots, null, null);
    }

    public MutationPlan {
        if (schemaVersion == 0) schemaVersion = 1; // Gson compatibility with journal v1.
        if (schemaVersion < 1 || schemaVersion > 2) {
            throw new IllegalArgumentException("unsupported mutation payload schema " + schemaVersion);
        }
        playerSlots = List.copyOf(playerSlots);
        Set<SlotRef> unique = new HashSet<>();
        for (SlotMutation mutation : playerSlots) {
            Objects.requireNonNull(mutation, "mutation");
            if (!unique.add(mutation.slot())) throw new IllegalArgumentException("duplicate affected slot");
        }
        if (schemaVersion == 1 && (escrow != null || settlement != null)) {
            throw new IllegalArgumentException("journal v1 cannot contain cursor escrow");
        }
        if (settlement != null && escrow == null) {
            throw new IllegalArgumentException("settlement requires old escrow authority");
        }
    }

    public static MutationPlan cursorStable(CursorEscrow escrow) {
        return cursorStable(List.of(), escrow);
    }

    public static MutationPlan cursorStable(List<SlotMutation> playerSlots, CursorEscrow escrow) {
        return new MutationPlan(2, playerSlots, Objects.requireNonNull(escrow), null);
    }

    public static MutationPlan settlement(
            List<SlotMutation> playerSlots, CursorEscrow oldEscrow, CursorSettlement settlement) {
        return new MutationPlan(2, playerSlots, Objects.requireNonNull(oldEscrow),
                Objects.requireNonNull(settlement));
    }

    public boolean isLegacy() { return schemaVersion == 1; }
    public boolean isCursorStable() { return schemaVersion == 2 && escrow != null && settlement == null; }
    public boolean isSettlement() { return schemaVersion == 2 && escrow != null && settlement != null; }

    public boolean matches(Phase phase, Map<SlotRef, SlotValue> observed) {
        if (observed.size() != playerSlots.size()) return false;
        for (SlotMutation mutation : playerSlots) {
            SlotValue expected = switch (phase) {
                case BEFORE -> mutation.before();
                case RESERVED -> mutation.reserved();
                case AFTER -> mutation.after();
            };
            if (!expected.equals(observed.get(mutation.slot()))) return false;
        }
        return true;
    }

    public enum Phase {
        BEFORE,
        RESERVED,
        AFTER
    }
}
