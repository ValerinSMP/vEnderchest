package com.valerin.venderchest.api.event;

import com.valerin.venderchest.api.ConflictType;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fired whenever vEnderchest's own authoritative session/revision protection rejects, refreshes,
 * or discards something on account of a conflicting concurrent operation. This is purely a
 * notification — by the time it fires, vEnderchest has already resolved the conflict safely on its
 * own; the event exists so an external monitor (e.g. vAntiDupe) can alert on the pattern.
 *
 * <p><b>Thread:</b> always fired on the main server thread.
 * <p><b>Not cancellable:</b> the conflict has already been resolved; there is nothing to veto.
 * {@code expectedRevision}/{@code actualRevision} are {@code -1} when not applicable to the
 * conflict type (e.g. {@link ConflictType#CONCURRENT_SESSION}).
 */
public final class VaultConflictEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sessionId;
    private final UUID ownerUuid;
    private final UUID actorUuid;
    private final String vaultId;
    private final ConflictType type;
    private final long expectedRevision;
    private final long actualRevision;
    private final Instant timestamp;
    private final String serverId;
    private final List<com.valerin.venderchest.api.SlotChange> attemptedChanges;

    public VaultConflictEvent(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId,
                               ConflictType type, long expectedRevision, long actualRevision,
                               Instant timestamp, String serverId) {
        this(sessionId, ownerUuid, actorUuid, vaultId, type, expectedRevision, actualRevision,
                timestamp, serverId, List.of());
    }

    public VaultConflictEvent(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId,
                               ConflictType type, long expectedRevision, long actualRevision,
                               Instant timestamp, String serverId,
                               List<com.valerin.venderchest.api.SlotChange> attemptedChanges) {
        this.sessionId = sessionId;
        this.ownerUuid = ownerUuid;
        this.actorUuid = actorUuid;
        this.vaultId = vaultId;
        this.type = type;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
        this.timestamp = timestamp;
        this.serverId = serverId;
        this.attemptedChanges = List.copyOf(attemptedChanges);
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getActorUuid() { return actorUuid; }
    public String getVaultId() { return vaultId; }
    public ConflictType getType() { return type; }
    public long getExpectedRevision() { return expectedRevision; }
    public long getActualRevision() { return actualRevision; }
    public Instant getTimestamp() { return timestamp; }
    public String getServerId() { return serverId; }
    /** Immutable attempted diff; populated for stale-revision commit conflicts when available. */
    public List<com.valerin.venderchest.api.SlotChange> getAttemptedChanges() { return attemptedChanges; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
