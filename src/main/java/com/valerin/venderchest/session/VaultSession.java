package com.valerin.venderchest.session;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative record of one open vault session. Deliberately Bukkit-free (the live inventory is
 * held only as an opaque {@code Object} token) so the session/revision state machine in
 * {@link VaultSessionRegistry} can be unit tested without a running server.
 */
public final class VaultSession {

    private final UUID sessionId;
    private final UUID ownerUuid;
    private final UUID actorUuid;
    private final String vaultId;
    private final Instant openedAt;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.OPENING);

    private volatile long loadedRevision = -1;
    private volatile long currentRevision = -1;
    private volatile Object inventoryToken;

    VaultSession(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId, Instant openedAt) {
        this.sessionId = sessionId;
        this.ownerUuid = ownerUuid;
        this.actorUuid = actorUuid;
        this.vaultId = vaultId;
        this.openedAt = openedAt;
    }

    VaultKey key() {
        return new VaultKey(ownerUuid, vaultId);
    }

    /** OPENING -&gt; ACTIVE. Fails if this session was already superseded/closed while its load was in flight. */
    boolean activate(long loadedRevision, Object inventoryToken) {
        if (!state.compareAndSet(SessionState.OPENING, SessionState.ACTIVE)) return false;
        this.loadedRevision = loadedRevision;
        this.currentRevision = loadedRevision;
        this.inventoryToken = inventoryToken;
        return true;
    }

    /** CAS transition; returns true only if this call performed it. */
    boolean transition(SessionState from, SessionState to) {
        return state.compareAndSet(from, to);
    }

    /** Unconditionally moves to CLOSED regardless of current state. */
    void forceClose() {
        state.set(SessionState.CLOSED);
    }

    void setCurrentRevision(long revision) {
        this.currentRevision = revision;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getActorUuid() { return actorUuid; }
    public String getVaultId() { return vaultId; }
    public Instant getOpenedAt() { return openedAt; }
    public SessionState getState() { return state.get(); }

    /** -1 until {@link #activate} has run. */
    public long getLoadedRevision() { return loadedRevision; }

    /** -1 until {@link #activate} has run; updated on each successful commit. */
    public long getCurrentRevision() { return currentRevision; }

    /** Null until {@link #activate} has run. The real Bukkit {@code Inventory}, kept as {@code Object}. */
    public Object getInventoryToken() { return inventoryToken; }
}
