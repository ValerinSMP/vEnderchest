package com.valerin.venderchest.api;

/** Why a {@link com.valerin.venderchest.api.event.VaultSessionClosedEvent} was fired. */
public enum CloseReason {
    /** Normal client-driven close (inventory close packet / server-side closeInventory()). */
    CLIENT_CLOSE,
    /** The same actor reopened the same vault; the previous session was committed and closed first. */
    REOPEN,
    /** Player disconnected while the session was open. */
    LOGOUT,
    /** Player was kicked while the session was open. */
    KICK,
    /** Closed as a side effect of a detected conflict (see {@link ConflictType}). */
    CONFLICT,
    /** Plugin disable / server shutdown. */
    SHUTDOWN,
    /** Forced closed by an administrative action (e.g. /ecadmin reload). */
    ADMIN_FORCE
}
