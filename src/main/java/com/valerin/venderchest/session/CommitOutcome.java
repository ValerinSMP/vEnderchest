package com.valerin.venderchest.session;

/** Precise result of a {@link VaultTransactionService#commitIfActive} attempt. */
public enum CommitOutcome {
    /** Another in-flight commit already owns this session; nothing happened here. */
    NOT_OWNED,
    /** Diff was empty - nothing to write, content unchanged. */
    NO_CHANGE,
    /** Save succeeded; the session's current revision now reflects the new persisted state. */
    COMMITTED,
    /** The persisted revision had already moved past what this session loaded; write was dropped. */
    CONFLICT
}
