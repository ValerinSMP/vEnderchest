package com.valerin.venderchest.session;

/** Outcome of {@link VaultSessionRegistry#beginOpen}. */
public sealed interface OpenAttempt {

    /** No session existed for the key; a new one was registered in OPENING state. */
    record Created(VaultSession session) implements OpenAttempt {}

    /**
     * A session already exists for the key, owned by the same actor. Nothing was registered.
     * The caller must fully commit and close {@code previous} before retrying {@code beginOpen}.
     */
    record Supersede(VaultSession previous) implements OpenAttempt {}

    /**
     * A session already exists for the key, owned by a different actor. Nothing was registered;
     * the caller should reject this open attempt and notify the actor.
     */
    record Rejected(VaultSession existing) implements OpenAttempt {}
}
