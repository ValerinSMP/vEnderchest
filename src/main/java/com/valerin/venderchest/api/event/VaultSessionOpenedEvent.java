package com.valerin.venderchest.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.Instant;
import java.util.UUID;

/**
 * Fired after a vault session has been authoritatively registered and its content loaded from the
 * database.
 *
 * <p><b>Thread:</b> always fired on the main server thread.
 * <p><b>Not cancellable:</b> this class intentionally does not implement {@code Cancellable} —
 * listeners may only observe. vEnderchest's duplication protection never consults listeners of
 * this event.
 */
public final class VaultSessionOpenedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sessionId;
    private final UUID ownerUuid;
    private final UUID actorUuid;
    private final String vaultId;
    private final long revision;
    private final Instant timestamp;
    private final String serverId;

    public VaultSessionOpenedEvent(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId,
                                    long revision, Instant timestamp, String serverId) {
        this.sessionId = sessionId;
        this.ownerUuid = ownerUuid;
        this.actorUuid = actorUuid;
        this.vaultId = vaultId;
        this.revision = revision;
        this.timestamp = timestamp;
        this.serverId = serverId;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getActorUuid() { return actorUuid; }
    public String getVaultId() { return vaultId; }
    public long getRevision() { return revision; }
    public Instant getTimestamp() { return timestamp; }
    public String getServerId() { return serverId; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
