package com.valerin.venderchest.crossserver;

import java.util.Objects;

public record CursorSettlement(
        Kind kind,
        Stage stage,
        long opSequence,
        SlotValue cursorBefore,
        SlotValue cursorAfter,
        CursorEscrow nextEscrow
) {
    public CursorSettlement {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(cursorBefore, "cursorBefore");
        Objects.requireNonNull(cursorAfter, "cursorAfter");
        if (opSequence < 1) throw new IllegalArgumentException("invalid settlement sequence");
    }

    public enum Stage { PLANNED, VAULT_APPLIED }

    public enum Kind {
        PLAYER_TO_CURSOR,
        CURSOR_TO_PLAYER,
        CURSOR_PLAYER_SWAP,
        CURSOR_TO_VAULT,
        VAULT_TO_CURSOR,
        CURSOR_VAULT_SWAP,
        DRAG,
        COLLECT,
        FALLBACK,
        ESCAPED_TAG
    }
}
