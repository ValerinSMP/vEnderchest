package com.valerin.venderchest.crossserver;

public enum RecoveryDecision {
    ABORT_WITHOUT_PLAYER_CHANGE,
    RESTORE_BEFORE_THEN_ABORT,
    APPLY_AFTER_THEN_ACK,
    ACK_WITHOUT_PLAYER_CHANGE,
    QUARANTINE
}
