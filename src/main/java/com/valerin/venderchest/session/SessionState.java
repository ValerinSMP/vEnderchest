package com.valerin.venderchest.session;

/** Internal session state machine. Mirrored publicly (1:1) by {@code api.PublicSessionState}. */
public enum SessionState {
    OPENING,
    ACTIVE,
    COMMITTING,
    CLOSED
}
