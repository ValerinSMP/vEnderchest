package com.valerin.venderchest.api;

/** Category of a detected {@link com.valerin.venderchest.api.event.VaultConflictEvent}. */
public enum ConflictType {
    /** A save was attempted against a revision that the persisted store had already moved past. */
    STALE_REVISION,
    /** A second session was attempted for a vault that already has an active session. */
    CONCURRENT_SESSION,
    /** A save could not be confirmed and was dropped rather than risk overwriting newer state. */
    LOST_UPDATE,
    /** A click/drag/commit was attributed to a session that is no longer the current one. */
    EVENT_FROM_CLOSED_SESSION,
    /** The same actor reopened a vault unusually quickly / repeatedly. */
    SUSPICIOUS_REOPEN
}
