package com.valerin.venderchest.api;

/** Semantic classification of what happened to a slot within a committed transaction. */
public enum SlotAction {
    INSERT,
    REMOVE,
    REPLACE,
    MOVE
}
