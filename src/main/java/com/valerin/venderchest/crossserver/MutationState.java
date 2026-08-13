package com.valerin.venderchest.crossserver;

public enum MutationState {
    PREPARED,
    DB_COMMITTED,
    CURSOR_STABLE,
    SETTLEMENT_PREPARED,
    ACKED,
    ABORTED,
    QUARANTINED
}
