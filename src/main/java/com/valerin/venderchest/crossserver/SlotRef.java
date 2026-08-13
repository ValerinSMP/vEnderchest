package com.valerin.venderchest.crossserver;

public record SlotRef(Area area, int slot) {

    public SlotRef {
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
    }

    public enum Area {
        PLAYER
    }
}
