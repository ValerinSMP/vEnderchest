package com.valerin.venderchest.crossserver;

import java.util.Objects;

public record SlotMutation(SlotRef slot, SlotValue before, SlotValue reserved, SlotValue after) {

    public SlotMutation {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(after, "after");
    }
}
