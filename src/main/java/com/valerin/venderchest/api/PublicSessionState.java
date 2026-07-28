package com.valerin.venderchest.api;

/** Public mirror of the plugin's internal session state machine. */
public enum PublicSessionState {
    OPENING,
    ACTIVE,
    COMMITTING,
    CLOSED
}
