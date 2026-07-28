package com.valerin.venderchest.api.event;

import com.valerin.venderchest.api.SlotChange;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fired only after a vault transaction has been accepted and durably confirmed by the database
 * (the optimistic-concurrency save succeeded) — never optimistically before that. Each
 * {@code transactionId} is generated exactly once, inside the commit path that can itself only run
 * once per session commit (guarded by {@code VaultSessionRegistry#beginCommit}'s compare-and-swap),
 * so this event fires exactly once per real transaction.
 *
 * <p><b>Thread:</b> always fired on the main server thread.
 * <p><b>Not cancellable:</b> the transaction is already durably committed by the time this fires;
 * there is nothing to veto. {@code getSlotChanges()} returns an immutable list of immutable
 * {@link SlotChange}s (defensively cloned {@code ItemStack}s) — listeners cannot mutate plugin state
 * through this event.
 */
public final class VaultTransactionCommittedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID transactionId;
    private final UUID sessionId;
    private final UUID ownerUuid;
    private final UUID actorUuid;
    private final String vaultId;
    private final long baseRevision;
    private final long committedRevision;
    private final Instant timestamp;
    private final String serverId;
    private final List<SlotChange> slotChanges;

    public VaultTransactionCommittedEvent(UUID transactionId, UUID sessionId, UUID ownerUuid, UUID actorUuid,
                                           String vaultId, long baseRevision, long committedRevision,
                                           Instant timestamp, String serverId, List<SlotChange> slotChanges) {
        this.transactionId = transactionId;
        this.sessionId = sessionId;
        this.ownerUuid = ownerUuid;
        this.actorUuid = actorUuid;
        this.vaultId = vaultId;
        this.baseRevision = baseRevision;
        this.committedRevision = committedRevision;
        this.timestamp = timestamp;
        this.serverId = serverId;
        this.slotChanges = List.copyOf(slotChanges);
    }

    public UUID getTransactionId() { return transactionId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getActorUuid() { return actorUuid; }
    public String getVaultId() { return vaultId; }
    public long getBaseRevision() { return baseRevision; }
    public long getCommittedRevision() { return committedRevision; }
    public Instant getTimestamp() { return timestamp; }
    public String getServerId() { return serverId; }
    public List<SlotChange> getSlotChanges() { return slotChanges; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
