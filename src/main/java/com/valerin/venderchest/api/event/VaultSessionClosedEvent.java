package com.valerin.venderchest.api.event;

import com.valerin.venderchest.api.CloseReason;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.Instant;
import java.util.UUID;

/**
 * Fired after a vault session has been closed and its key freed.
 *
 * <p><b>Thread:</b> always fired on the main server thread.
 * <p><b>Not cancellable:</b> a session is already gone by the time this fires; there is nothing
 * to veto.
 */
public final class VaultSessionClosedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sessionId;
    private final UUID ownerUuid;
    private final UUID actorUuid;
    private final String vaultId;
    private final long lastRevision;
    private final CloseReason reason;
    private final Instant timestamp;
    private final String serverId;

    public VaultSessionClosedEvent(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId,
                                    long lastRevision, CloseReason reason, Instant timestamp, String serverId) {
        this.sessionId = sessionId;
        this.ownerUuid = ownerUuid;
        this.actorUuid = actorUuid;
        this.vaultId = vaultId;
        this.lastRevision = lastRevision;
        this.reason = reason;
        this.timestamp = timestamp;
        this.serverId = serverId;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getActorUuid() { return actorUuid; }
    public String getVaultId() { return vaultId; }
    public long getLastRevision() { return lastRevision; }
    public CloseReason getReason() { return reason; }
    public Instant getTimestamp() { return timestamp; }
    public String getServerId() { return serverId; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
