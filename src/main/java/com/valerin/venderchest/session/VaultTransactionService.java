package com.valerin.venderchest.session;

import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.api.ConflictType;
import com.valerin.venderchest.api.LocationKeys;
import com.valerin.venderchest.api.SlotChange;
import com.valerin.venderchest.api.event.VaultConflictEvent;
import com.valerin.venderchest.api.event.VaultSessionClosedEvent;
import com.valerin.venderchest.api.event.VaultSessionOpenedEvent;
import com.valerin.venderchest.api.event.VaultTransactionCommittedEvent;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bukkit-facing orchestrator that turns a {@link VaultSession}'s in-memory content into a
 * persisted, revision-checked commit, and fires the corresponding public API events. This is the
 * only place in the plugin that writes vault content to the database — every open/close/navigate/
 * autosave/quit/shutdown path funnels through {@link #commitIfActive}.
 *
 * <p>All public methods here must be called from the main thread; each schedules its own async DB
 * work and always hops back to the main thread before touching the registry, firing events, or
 * invoking callbacks.
 */
public final class VaultTransactionService {

    private final Plugin plugin;
    private final Storage storage;
    private final VaultSessionRegistry registry;
    private final VaultAuditLog audit;
    private final String serverId;
    private final Map<UUID, List<Runnable>> afterCommit = new HashMap<>();
    private volatile boolean backupsEnabled;
    private volatile int backupsKeepPerVault;

    public VaultTransactionService(Plugin plugin, Storage storage, VaultSessionRegistry registry,
                                    VaultAuditLog audit, String serverId, boolean backupsEnabled, int backupsKeepPerVault) {
        this.plugin = plugin;
        this.storage = storage;
        this.registry = registry;
        this.audit = audit;
        this.serverId = serverId;
        this.backupsEnabled = backupsEnabled;
        this.backupsKeepPerVault = backupsKeepPerVault;
    }

    public void setBackupsEnabled(boolean backupsEnabled) {
        this.backupsEnabled = backupsEnabled;
    }

    public void setBackupsKeepPerVault(int backupsKeepPerVault) {
        this.backupsKeepPerVault = backupsKeepPerVault;
    }

    /**
     * Commits {@code session} if (and only if) it is still ACTIVE: diffs {@code originalSnapshot}
     * against {@code currentSnapshot}, saves via optimistic-concurrency CAS, and on success updates
     * the registry and fires {@link VaultTransactionCommittedEvent}. On a revision conflict the
     * write is dropped entirely and {@link VaultConflictEvent} is fired instead — the caller's
     * in-memory content is never trusted over what the database confirmed, so a stale session can
     * never complete a transfer of items it no longer has authority over.
     *
     * <p>If another caller already owns committing this session (its state is not ACTIVE — e.g. it
     * is OPENING, already COMMITTING, or CLOSED), this is a safe no-op: {@code onSettled} still
     * runs (with {@link CommitOutcome#NOT_OWNED}), but no I/O happens and no event fires. This is
     * what makes it safe to call this method from multiple trigger points (close, quit, kick,
     * autosave, reopen) without coordinating who "owns" a given commit — whichever caller wins the
     * underlying compare-and-swap performs it, every other caller's attempt is a no-op.
     *
     * @param onSettled invoked on the main thread once this attempt is fully resolved, with the
     *                  precise outcome — the caller uses this both to decide whether it, rather than
     *                  some other in-flight caller, is responsible for closing the session
     *                  afterward, and whether {@code currentSnapshot} is safe to treat as the
     *                  vault's true current content (it is on {@code COMMITTED}/{@code NO_CHANGE},
     *                  never on {@code CONFLICT} — a rejected write must not be trusted).
     */
    public void commitIfActive(VaultSession session, ItemStack[] originalSnapshot, ItemStack[] currentSnapshot,
                                Consumer<CommitOutcome> onSettled) {
        if (!registry.beginCommit(session.getSessionId())) {
            onSettled.accept(CommitOutcome.NOT_OWNED);
            return;
        }

        List<SlotDiff> diffs = TransactionDiffEngine.diff(toSnapshots(originalSnapshot), toSnapshots(currentSnapshot));
        if (diffs.isEmpty()) {
            // Nothing changed - still release COMMITTING back to ACTIVE so the session stays usable
            // if the caller doesn't immediately close it (e.g. an autosave tick with no dirty content).
            registry.completeCommit(session.getSessionId(), session.getCurrentRevision());
            settle(session, onSettled, CommitOutcome.NO_CHANGE);
            return;
        }

        long startNanos = System.nanoTime();
        UUID owner = session.getOwnerUuid();
        int page = Integer.parseInt(session.getVaultId());
        long baseRevision = session.getCurrentRevision();
        ItemStack[] toSave = cloneArray(currentSnapshot);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Storage.SaveResult result = session.isCrossServer()
                    ? storage.savePageIfRevisionAndFence(owner, page, toSave, baseRevision,
                    session.getNetworkFence())
                    : storage.savePageIfRevision(owner, page, toSave, baseRevision);
            if (result instanceof Storage.SaveResult.Success success) {
                writeBackup(owner, page, success.newRevision(), toSave); // still off the main thread here
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                CommitOutcome outcome;
                if (result instanceof Storage.SaveResult.Success success) {
                    registry.completeCommit(session.getSessionId(), success.newRevision());
                    audit.commit(session, baseRevision, success.newRevision(), diffs, durationMs);
                    fireCommitted(session, baseRevision, success.newRevision(), diffs, originalSnapshot, currentSnapshot);
                    outcome = CommitOutcome.COMMITTED;
                } else if (result instanceof Storage.SaveResult.Conflict conflict) {
                    audit.conflict(session.getSessionId(), owner, session.getActorUuid(), session.getVaultId(),
                            ConflictType.STALE_REVISION, baseRevision, conflict.currentRevision());
                    fireConflict(session, ConflictType.STALE_REVISION, baseRevision, conflict.currentRevision(),
                            diffs, originalSnapshot, currentSnapshot);
                    // Leave the session in COMMITTING - the caller is expected to close() it and
                    // reload fresh state rather than retry blindly with data we know is stale.
                    outcome = CommitOutcome.CONFLICT;
                } else {
                    long observed = result instanceof Storage.SaveResult.StaleFence stale
                            ? stale.currentFence() : -1;
                    audit.conflict(session.getSessionId(), owner, session.getActorUuid(), session.getVaultId(),
                            ConflictType.LOST_UPDATE, baseRevision, observed);
                    plugin.getLogger().warning("Vault save failed closed for owner=" + owner
                            + " page=" + page + " result=" + result.getClass().getSimpleName());
                    outcome = CommitOutcome.CONFLICT;
                }
                settle(session, onSettled, outcome);
            });
        });
    }

    /**
     * Runs after an already in-flight commit settles. Used by close/reopen/logout paths that race
     * an autosave so they do not leak an ACTIVE registry entry after the autosave callback.
     */
    public void afterCurrentCommit(VaultSession session, Runnable callback) {
        if (session.getState() != SessionState.COMMITTING) {
            callback.run();
            return;
        }
        afterCommit.computeIfAbsent(session.getSessionId(), ignored -> new ArrayList<>()).add(callback);
    }

    private void settle(VaultSession session, Consumer<CommitOutcome> ownerCallback, CommitOutcome outcome) {
        ownerCallback.accept(outcome);
        List<Runnable> waiters = afterCommit.remove(session.getSessionId());
        if (waiters != null) {
            for (Runnable waiter : List.copyOf(waiters)) {
                waiter.run();
            }
        }
    }

    /**
     * Synchronous variant of {@link #commitIfActive} for the one deliberate exception to "no SQL
     * on the main thread": plugin disable/reload, where Bukkit does not reliably run scheduled
     * async tasks to completion. Performs the DB write on the calling thread. Only ever call this
     * at shutdown/reload — every other trigger (open, close, navigate, autosave, quit, kick) must
     * go through {@link #commitIfActive}.
     */
    public void commitSynchronously(VaultSession session, ItemStack[] originalSnapshot, ItemStack[] currentSnapshot) {
        if (!registry.beginCommit(session.getSessionId())) return;

        List<SlotDiff> diffs = TransactionDiffEngine.diff(toSnapshots(originalSnapshot), toSnapshots(currentSnapshot));
        if (diffs.isEmpty()) {
            registry.completeCommit(session.getSessionId(), session.getCurrentRevision());
            return;
        }

        UUID owner = session.getOwnerUuid();
        int page = Integer.parseInt(session.getVaultId());
        long baseRevision = session.getCurrentRevision();
        Storage.SaveResult result = session.isCrossServer()
                ? storage.savePageIfRevisionAndFence(owner, page, cloneArray(currentSnapshot), baseRevision,
                session.getNetworkFence())
                : storage.savePageIfRevision(owner, page, cloneArray(currentSnapshot), baseRevision);

        if (result instanceof Storage.SaveResult.Success success) {
            registry.completeCommit(session.getSessionId(), success.newRevision());
            audit.commit(session, baseRevision, success.newRevision(), diffs, 0);
            fireCommitted(session, baseRevision, success.newRevision(), diffs, originalSnapshot, currentSnapshot);
            writeBackup(owner, page, success.newRevision(), cloneArray(currentSnapshot));
        } else if (result instanceof Storage.SaveResult.Conflict conflict) {
            audit.conflict(session.getSessionId(), owner, session.getActorUuid(), session.getVaultId(),
                    ConflictType.STALE_REVISION, baseRevision, conflict.currentRevision());
            fireConflict(session, ConflictType.STALE_REVISION, baseRevision, conflict.currentRevision(),
                    diffs, originalSnapshot, currentSnapshot);
        } else {
            long observed = result instanceof Storage.SaveResult.StaleFence stale ? stale.currentFence() : -1;
            audit.conflict(session.getSessionId(), owner, session.getActorUuid(), session.getVaultId(),
                    ConflictType.LOST_UPDATE, baseRevision, observed);
            plugin.getLogger().warning("Vault synchronous save failed closed for owner=" + owner
                    + " page=" + page + " result=" + result.getClass().getSimpleName());
        }
    }

    /**
     * Stores a revision snapshot after a successful commit, pruning down to
     * {@link #backupsKeepPerVault} afterward. Called only from a real commit success (never on
     * conflict, never on a no-op), so every backup row corresponds to an actual, distinct change.
     * Callers must already be off the main thread (or, for {@link #commitSynchronously}, be in the
     * one documented shutdown-time exception).
     */
    private void writeBackup(UUID owner, int page, long revision, ItemStack[] items) {
        if (!backupsEnabled) return;
        storage.saveBackup(owner, page, revision, "COMMIT", items);
        storage.pruneBackups(owner, page, backupsKeepPerVault);
    }

    public void fireOpened(VaultSession session) {
        Bukkit.getPluginManager().callEvent(new VaultSessionOpenedEvent(
                session.getSessionId(), session.getOwnerUuid(), session.getActorUuid(), session.getVaultId(),
                session.getCurrentRevision(), Instant.now(), serverId));
        audit.opened(session);
    }

    public void fireClosed(VaultSession session, CloseReason reason) {
        Bukkit.getPluginManager().callEvent(new VaultSessionClosedEvent(
                session.getSessionId(), session.getOwnerUuid(), session.getActorUuid(), session.getVaultId(),
                session.getCurrentRevision(), reason, Instant.now(), serverId));
        audit.closed(session, reason);
    }

    /** Fired when a second actor is rejected from opening a vault another actor already holds open. */
    public void fireConcurrentSessionConflict(UUID ownerUuid, UUID actorUuid, String vaultId, VaultSession blocking) {
        Bukkit.getPluginManager().callEvent(new VaultConflictEvent(
                blocking.getSessionId(), ownerUuid, actorUuid, vaultId,
                ConflictType.CONCURRENT_SESSION, -1, -1, Instant.now(), serverId));
        audit.openRejected(ownerUuid, actorUuid, vaultId, blocking.getSessionId());
    }

    /** Fired when the same actor reopens a vault they already had open, without a clean prior close. */
    public void fireSuspiciousReopen(VaultSession previous) {
        Bukkit.getPluginManager().callEvent(new VaultConflictEvent(
                previous.getSessionId(), previous.getOwnerUuid(), previous.getActorUuid(), previous.getVaultId(),
                ConflictType.SUSPICIOUS_REOPEN, previous.getCurrentRevision(), previous.getCurrentRevision(),
                Instant.now(), serverId));
        audit.reopenDetected(previous.getOwnerUuid(), previous.getActorUuid(), previous.getVaultId(), previous.getSessionId());
    }

    /** Fired when a click/drag/commit is attributed to a session that is no longer current. */
    public void fireEventFromClosedSession(UUID ownerUuid, UUID actorUuid, String vaultId, UUID staleSessionId) {
        Bukkit.getPluginManager().callEvent(new VaultConflictEvent(
                staleSessionId, ownerUuid, actorUuid, vaultId,
                ConflictType.EVENT_FROM_CLOSED_SESSION, -1, -1, Instant.now(), serverId));
        audit.conflict(staleSessionId, ownerUuid, actorUuid, vaultId, ConflictType.EVENT_FROM_CLOSED_SESSION, -1, -1);
    }

    private void fireCommitted(VaultSession session, long baseRevision, long newRevision, List<SlotDiff> diffs,
                                ItemStack[] before, ItemStack[] after) {
        List<SlotChange> changes = toSlotChanges(session, diffs, before, after);
        Bukkit.getPluginManager().callEvent(new VaultTransactionCommittedEvent(
                UUID.randomUUID(), session.getSessionId(), session.getOwnerUuid(), session.getActorUuid(),
                session.getVaultId(), baseRevision, newRevision, Instant.now(), serverId, changes));
    }

    private List<SlotChange> toSlotChanges(
            VaultSession session,
            List<SlotDiff> diffs,
            ItemStack[] before,
            ItemStack[] after
    ) {
        List<SlotChange> changes = new ArrayList<>();
        for (SlotDiff d : diffs) {
            ItemStack b = d.slot() < before.length ? before[d.slot()] : null;
            ItemStack a = d.slot() < after.length ? after[d.slot()] : null;
            String key = LocationKeys.of(session.getOwnerUuid(), session.getVaultId(), d.slot());
            changes.add(new SlotChange(d.slot(), d.action(), b, a, d.amountBefore(), d.amountAfter(), key));
        }
        return List.copyOf(changes);
    }

    private void fireConflict(
            VaultSession session,
            ConflictType type,
            long expectedRevision,
            long actualRevision,
            List<SlotDiff> diffs,
            ItemStack[] before,
            ItemStack[] after
    ) {
        Bukkit.getPluginManager().callEvent(new VaultConflictEvent(
                session.getSessionId(), session.getOwnerUuid(), session.getActorUuid(), session.getVaultId(),
                type, expectedRevision, actualRevision, Instant.now(), serverId,
                toSlotChanges(session, diffs, before, after)));
    }

    private static ItemSnapshot[] toSnapshots(ItemStack[] items) {
        ItemSnapshot[] out = new ItemSnapshot[items.length];
        for (int i = 0; i < items.length; i++) out[i] = BukkitItemSnapshot.of(items[i]);
        return out;
    }

    private static ItemStack[] cloneArray(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) out[i] = items[i] == null ? null : items[i].clone();
        return out;
    }
}
