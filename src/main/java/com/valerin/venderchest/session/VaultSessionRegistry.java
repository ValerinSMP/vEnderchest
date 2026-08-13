package com.valerin.venderchest.session;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for which vault session, if any, currently owns each {@link VaultKey}.
 * This is the actual fix for the vault duplication race: it guarantees at most one live session
 * per (owner, vaultId) at any time, and every state transition a session goes through is
 * compare-and-swapped so a given transition (e.g. commit) can only ever be performed once.
 *
 * <p>Pure Java, no Bukkit dependency, so it can be unit tested without a running server.
 *
 * <p><b>Threading contract:</b> every method here must only be called from the server's main
 * thread. The registry uses concurrent collections purely as a defensive safety net; the actual
 * safety of the open/commit/close *sequence* comes from Bukkit's single-threaded main-thread
 * execution model, which callers (see {@code GuiManager} / {@code VaultTransactionService}) must
 * respect by always hopping back to the main thread before calling into this class. Async DB
 * results must never mutate a session directly — they only ever trigger a main-thread callback
 * that then calls back into this registry.
 */
public final class VaultSessionRegistry {

    private final Map<VaultKey, VaultSession> current = new ConcurrentHashMap<>();
    private final Map<UUID, VaultSession> bySessionId = new ConcurrentHashMap<>();

    /**
     * Attempts to reserve {@code key = (ownerUuid, vaultId)} for a new session belonging to
     * {@code actorUuid}. Never creates two live sessions for the same key.
     */
    public OpenAttempt beginOpen(UUID ownerUuid, UUID actorUuid, String vaultId) {
        VaultKey key = new VaultKey(ownerUuid, vaultId);
        VaultSession existing = current.get(key);
        if (existing == null) {
            VaultSession session = new VaultSession(UUID.randomUUID(), ownerUuid, actorUuid, vaultId,
                    Instant.now(), VaultWriter.SINGLE_SERVER, 0);
            current.put(key, session);
            bySessionId.put(session.getSessionId(), session);
            return new OpenAttempt.Created(session);
        }
        if (existing.getActorUuid().equals(actorUuid)) {
            return new OpenAttempt.Supersede(existing);
        }
        return new OpenAttempt.Rejected(existing);
    }

    /**
     * OPENING -&gt; ACTIVE, recording the revision the content was actually loaded at and an opaque
     * token identifying the live inventory instance. Returns false if the session was superseded
     * or force-closed while its load was in flight — the caller must then discard the load result
     * without opening anything, rather than presenting stale/orphaned state to a player.
     */
    public boolean activate(UUID sessionId, long loadedRevision, Object inventoryToken) {
        VaultSession session = bySessionId.get(sessionId);
        return session != null && session.activate(loadedRevision, inventoryToken);
    }

    public OpenAttempt beginOpenCrossServer(
            UUID ownerUuid, UUID actorUuid, String vaultId, UUID sessionId, long fence) {
        VaultKey key = new VaultKey(ownerUuid, vaultId);
        VaultSession existing = current.get(key);
        if (existing == null) {
            VaultSession session = new VaultSession(sessionId, ownerUuid, actorUuid, vaultId,
                    Instant.now(), VaultWriter.CROSS_SERVER, fence);
            current.put(key, session);
            bySessionId.put(session.getSessionId(), session);
            return new OpenAttempt.Created(session);
        }
        if (existing.getActorUuid().equals(actorUuid)) return new OpenAttempt.Supersede(existing);
        return new OpenAttempt.Rejected(existing);
    }

    public Optional<VaultSession> switchCrossServerPage(UUID sessionId, String nextVaultId) {
        VaultSession previous = bySessionId.get(sessionId);
        if (previous == null || !previous.isCrossServer() || previous.getState() != SessionState.ACTIVE) {
            return Optional.empty();
        }
        VaultKey nextKey = new VaultKey(previous.getOwnerUuid(), nextVaultId);
        VaultSession occupant = current.get(nextKey);
        if (occupant != null && occupant != previous) return Optional.empty();

        bySessionId.remove(sessionId, previous);
        current.remove(previous.key(), previous);
        previous.forceClose();
        VaultSession next = new VaultSession(sessionId, previous.getOwnerUuid(), previous.getActorUuid(),
                nextVaultId, Instant.now(), VaultWriter.CROSS_SERVER, previous.getNetworkFence());
        current.put(nextKey, next);
        bySessionId.put(sessionId, next);
        return Optional.of(next);
    }

    public boolean advanceNetworkRevision(UUID sessionId, long expectedRevision, long newRevision) {
        VaultSession session = bySessionId.get(sessionId);
        return session != null && session.advanceNetworkRevision(expectedRevision, newRevision);
    }

    /** ACTIVE -&gt; COMMITTING. CAS'd: succeeds at most once per session, so a commit can never double-fire. */
    public boolean beginCommit(UUID sessionId) {
        VaultSession session = bySessionId.get(sessionId);
        return session != null && session.transition(SessionState.ACTIVE, SessionState.COMMITTING);
    }

    /** COMMITTING -&gt; ACTIVE, recording the newly persisted revision. */
    public boolean completeCommit(UUID sessionId, long newRevision) {
        VaultSession session = bySessionId.get(sessionId);
        if (session == null || !session.transition(SessionState.COMMITTING, SessionState.ACTIVE)) return false;
        session.setCurrentRevision(newRevision);
        return true;
    }

    /**
     * Any non-CLOSED state -&gt; CLOSED, and removes the session from the registry (freeing its key
     * for a new {@code beginOpen}) if it is still the one currently registered. Safe to call
     * multiple times for the same session id — subsequent calls are a no-op since the session is
     * no longer resolvable by id after the first successful close.
     */
    public Optional<VaultSession> close(UUID sessionId) {
        VaultSession session = bySessionId.remove(sessionId);
        if (session == null) return Optional.empty();
        session.forceClose();
        current.remove(session.key(), session);
        return Optional.of(session);
    }

    public Optional<VaultSession> current(VaultKey key) {
        return Optional.ofNullable(current.get(key));
    }

    public Optional<VaultSession> bySessionId(UUID sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    /**
     * Returns true only while this exact inventory instance belongs to the current ACTIVE session.
     * A COMMITTING GUI is deliberately non-interactive: its snapshot is already frozen for a
     * revision-checked save and accepting another click would create an unpersisted mutation.
     */
    public boolean isActive(UUID sessionId, Object inventoryToken) {
        VaultSession session = bySessionId.get(sessionId);
        return session != null
                && session.getState() == SessionState.ACTIVE
                && session.getInventoryToken() == inventoryToken
                && current.get(session.key()) == session;
    }

    /** Sessions currently ACTIVE (loaded, open, not mid-commit). Used for autosave and the public API. */
    public Collection<VaultSession> allActive() {
        return current.values().stream()
                .filter(s -> s.getState() == SessionState.ACTIVE)
                .toList();
    }

    /** Every session currently tracked, in any non-CLOSED state. Bounded by online player count. */
    public Collection<VaultSession> allTracked() {
        return List.copyOf(current.values());
    }
}
