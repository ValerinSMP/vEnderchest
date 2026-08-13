package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.session.VaultSession;
import com.valerin.venderchest.storage.NetworkSessionStore;
import com.valerin.venderchest.storage.Storage;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CrossServerMutationController {

    private final Supplier<CrossServerLifecycle.State> lifecycleState;
    private final BackendProvider backends;
    private final ViewPort views;
    private final Executor asyncExecutor;
    private final Executor mainExecutor;
    private final Map<UUID, SessionControl> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> ownerMutations = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CrossServerMutationController(
            CrossServerLifecycle lifecycle,
            NetworkRuntimeFactory runtimes,
            CrossServerRecoveryService.PlayerDataPort playerData,
            ViewPort views,
            Executor asyncExecutor,
            Executor mainExecutor
    ) {
        this.lifecycleState = lifecycle::state;
        this.backends = () -> {
            NetworkRuntimeFactory.Runtime runtime = runtimes.activeRuntime();
            return runtime == null ? null : new RuntimeBackend(runtime, playerData, asyncExecutor, mainExecutor);
        };
        this.views = views;
        this.asyncExecutor = asyncExecutor;
        this.mainExecutor = mainExecutor;
    }

    CrossServerMutationController(
            Supplier<CrossServerLifecycle.State> lifecycleState,
            BackendProvider backends,
            ViewPort views,
            Executor asyncExecutor,
            Executor mainExecutor
    ) {
        this.lifecycleState = lifecycleState;
        this.backends = backends;
        this.views = views;
        this.asyncExecutor = asyncExecutor;
        this.mainExecutor = mainExecutor;
    }

    public void prepareOpen(UUID ownerUuid, UUID actorUuid, int page, long actorRequest,
                            Consumer<OpenOutcome> callback) {
        OpenRequest request = new OpenRequest(UUID.randomUUID(), ownerUuid, actorUuid, page, actorRequest);
        if (closed.get() || lifecycleState.get() != CrossServerLifecycle.State.ACTIVE) {
            callback.accept(OpenOutcome.failed(OpenResult.UNAVAILABLE));
            return;
        }
        asyncExecutor.execute(() -> acquireForOpen(request, callback, false));
    }

    private void acquireForOpen(
            OpenRequest request, Consumer<OpenOutcome> callback, boolean afterRecovery) {
        Backend backend = backends.current();
        if (backend == null || closed.get()) {
            finishOpen(callback, OpenResult.UNAVAILABLE, request, null);
            return;
        }
        RedisLeaseCoordinator.AcquireResult result = backend.acquire(request.ownerUuid(), request.sessionId());
        if (result instanceof RedisLeaseCoordinator.AcquireResult.Granted granted) {
            SessionControl control = new SessionControl(request.sessionId(), request.ownerUuid(),
                    request.actorUuid(), request.page(), request.actorRequest(),
                    granted.fence(), backend);
            sessions.put(request.sessionId(), control);
            finishOpen(callback, OpenResult.GRANTED, request, control);
        } else if (result instanceof RedisLeaseCoordinator.AcquireResult.RecoveryRequired && !afterRecovery) {
            recoverThenRetry(request, callback, backend);
        } else if (result instanceof RedisLeaseCoordinator.AcquireResult.Busy) {
            finishOpen(callback, OpenResult.BUSY, request, null);
        } else if (result instanceof RedisLeaseCoordinator.AcquireResult.RecoveryRequired) {
            finishOpen(callback, OpenResult.RECOVERY_PENDING, request, null);
        } else {
            finishOpen(callback, OpenResult.UNAVAILABLE, request, null);
        }
    }

    private void recoverThenRetry(
            OpenRequest request,
            Consumer<OpenOutcome> callback,
            Backend backend
    ) {
        backend.recover(request.ownerUuid()).whenComplete((outcome, error) -> {
            if (error != null || outcome == CrossServerRecoveryService.Outcome.PROVIDER_UNAVAILABLE) {
                finishOpen(callback, OpenResult.UNAVAILABLE, request, null);
            } else if (outcome == CrossServerRecoveryService.Outcome.QUARANTINED) {
                finishOpen(callback, OpenResult.QUARANTINED, request, null);
            } else if (outcome == CrossServerRecoveryService.Outcome.CLEAR
                    || outcome == CrossServerRecoveryService.Outcome.RECOVERED) {
                asyncExecutor.execute(() -> acquireForOpen(request, callback, true));
            } else {
                finishOpen(callback, OpenResult.RECOVERY_PENDING, request, null);
            }
        });
    }

    private void finishOpen(
            Consumer<OpenOutcome> callback, OpenResult result, OpenRequest request, SessionControl control) {
        if (closed.get()) {
            if (control != null) control.backend.release(request.sessionId());
            return;
        }
        mainExecutor.execute(() -> {
            if (closed.get()) {
                if (control != null) asyncExecutor.execute(() -> control.backend.release(request.sessionId()));
                return;
            }
            callback.accept(control == null ? OpenOutcome.failed(result)
                    : new OpenOutcome(result, request.sessionId(), control.fence));
        });
    }

    public boolean isTracked(UUID sessionId) { return sessions.containsKey(sessionId); }
    public boolean mayUseView(UUID sessionId, long fence) {
        SessionControl control = sessions.get(sessionId);
        return control != null && !closed.get() && !control.closeRequested && control.fence == fence
                && lifecycleState.get() == CrossServerLifecycle.State.ACTIVE
                && control.backend.mayAcceptMutation(sessionId);
    }
    public boolean hasInFlight(UUID sessionId) {
        SessionControl control = sessions.get(sessionId);
        return control != null && control.operationPending;
    }

    public CursorEscrow cursorEscrow(UUID sessionId) {
        SessionControl control = sessions.get(sessionId);
        return control == null ? null : control.escrow;
    }

    public boolean cursorDetached(UUID sessionId) {
        SessionControl control = sessions.get(sessionId);
        return control != null && control.cursorDetached;
    }

    public boolean markCursorDetached(UUID sessionId, CursorEscrow expected) {
        SessionControl control = sessions.get(sessionId);
        if (control == null || control.escrow == null || !control.escrow.equals(expected)) return false;
        control.cursorDetached = true;
        return true;
    }

    public void quarantineCursor(UUID sessionId) {
        SessionControl control = sessions.get(sessionId);
        if (control == null || control.mutationId == null) return;
        control.abandoned = true;
        control.closeRequested = true;
        asyncExecutor.execute(() -> {
            control.backend.quarantine(control.mutationId);
            park(control, control.mutationId);
        });
    }

    public boolean hasAnyTrackedOrInFlight() {
        return !sessions.isEmpty() || !ownerMutations.isEmpty();
    }

    public boolean rebindView(UUID sessionId, int page, long actorRequest) {
        SessionControl control = sessions.get(sessionId);
        if (control == null || control.mutationId != null || control.operationPending || control.closeRequested
                || !mayUseView(sessionId, control.fence)) return false;
        control.page = page;
        control.actorRequest = actorRequest;
        return true;
    }

    /** Main-thread admission. The event must already be cancelled before this is called. */
    public SubmitResult submit(VaultSession session, PlannedMutation plan) {
        SessionControl control = sessions.get(session.getSessionId());
        if (control == null || closed.get() || !session.isCrossServer()
                || session.getNetworkFence() != control.fence) return SubmitResult.STALE;
        if (!control.backend.mayAcceptMutation(session.getSessionId())) return SubmitResult.FROZEN;
        UUID mutationId = UUID.randomUUID();
        if (control.mutationId != null || control.operationPending
                || ownerMutations.putIfAbsent(control.ownerUuid, mutationId) != null) {
            return SubmitResult.BUSY;
        }
        control.mutationId = mutationId;
        control.operationPending = true;
        long sequence = ++control.mutationSequence;
        MutationJournalRecord journal = new MutationJournalRecord(mutationId, control.ownerUuid,
                control.actorUuid, control.sessionId, sequence, control.fence, control.page,
                session.getCurrentRevision(), null, MutationState.PREPARED, plan.playerPlan(),
                plan.vaultBefore(), plan.vaultAfter());
        asyncExecutor.execute(() -> prepare(control, journal, plan));
        return SubmitResult.ACCEPTED;
    }

    /** Main-thread admission for emulated vanilla cursor actions. */
    public SubmitResult submitCursor(VaultSession session, CrossServerInventoryPlanner.CursorActionPlan action) {
        SessionControl control = sessions.get(session.getSessionId());
        if (control == null || closed.get() || !session.isCrossServer()
                || session.getNetworkFence() != control.fence) return SubmitResult.STALE;
        if (!control.backend.mayAcceptMutation(session.getSessionId())) return SubmitResult.FROZEN;
        if (control.operationPending) return SubmitResult.BUSY;

        CursorEscrow current = control.escrow;
        if (current == null) {
            if (!action.cursorBefore().isEmpty() || action.cursorAfter().isEmpty()) return SubmitResult.STALE;
            UUID mutationId = UUID.randomUUID();
            CursorEscrow escrow = views.createEscrow(mutationId, 1, action.cursorAfter(), java.util.List.of());
            if (control.mutationId != null
                    || ownerMutations.putIfAbsent(control.ownerUuid, mutationId) != null) {
                return SubmitResult.BUSY;
            }
            MutationPlan playerPlan = MutationPlan.cursorStable(action.playerSlots(), escrow);
            PlannedMutation plan = new PlannedMutation(playerPlan, action.vaultBefore(), action.vaultAfter());
            control.mutationId = mutationId;
            control.operationPending = true;
            long sequence = ++control.mutationSequence;
            MutationJournalRecord journal = new MutationJournalRecord(mutationId, control.ownerUuid,
                    control.actorUuid, control.sessionId, sequence, control.fence, control.page,
                    session.getCurrentRevision(), null, MutationState.PREPARED, playerPlan,
                    plan.vaultBefore(), plan.vaultAfter());
            asyncExecutor.execute(() -> prepare(control, journal, plan));
            return SubmitResult.ACCEPTED;
        }

        if (control.mutationId == null || !current.canonical().equals(action.cursorBefore())) {
            return SubmitResult.STALE;
        }
        long nextSequence = current.opSequence() + 1;
        CursorEscrow next = action.cursorAfter().isEmpty() ? null
                : views.createEscrow(control.mutationId, nextSequence, action.cursorAfter(), java.util.List.of());
        CursorSettlement settlement = new CursorSettlement(settlementKind(action),
                CursorSettlement.Stage.PLANNED, nextSequence, current.projection(),
                next == null ? SlotValue.empty() : next.projection(), next);
        MutationPlan plan = MutationPlan.settlement(action.playerSlots(), current, settlement);
        control.operationPending = true;
        asyncExecutor.execute(() -> prepareSettlement(control, session, action, plan));
        return SubmitResult.ACCEPTED;
    }

    /** Main-thread close path after the exact tagged projection has already been cleared. */
    public SubmitResult submitDetachedSettlement(VaultSession session, MutationPlan plan,
                                                 String unchangedVault) {
        SessionControl control = sessions.get(session.getSessionId());
        if (control == null || control.operationPending || control.escrow == null
                || control.mutationId == null || !plan.isSettlement()
                || plan.settlement().kind() != CursorSettlement.Kind.FALLBACK
                || !control.cursorDetached || !control.escrow.equals(plan.escrow())) return SubmitResult.STALE;
        control.operationPending = true;
        asyncExecutor.execute(() -> prepareSettlement(control, session,
                new CrossServerInventoryPlanner.CursorActionPlan(plan.playerSlots(),
                        plan.escrow().canonical(), SlotValue.empty(), unchangedVault, unchangedVault), plan));
        return SubmitResult.ACCEPTED;
    }

    private CursorSettlement.Kind settlementKind(CrossServerInventoryPlanner.CursorActionPlan action) {
        if (action.changesVault() && action.changesPlayer()) return CursorSettlement.Kind.DRAG;
        if (action.changesVault()) return action.cursorAfter().isEmpty()
                ? CursorSettlement.Kind.CURSOR_TO_VAULT : CursorSettlement.Kind.CURSOR_VAULT_SWAP;
        if (action.changesPlayer()) return action.cursorAfter().isEmpty()
                ? CursorSettlement.Kind.CURSOR_TO_PLAYER : CursorSettlement.Kind.CURSOR_PLAYER_SWAP;
        return CursorSettlement.Kind.COLLECT;
    }

    private void prepareSettlement(SessionControl control, VaultSession session,
                                   CrossServerInventoryPlanner.CursorActionPlan action, MutationPlan plan) {
        boolean prepared = control.backend.prepareSettlement(control.mutationId,
                plan.escrow().opSequence(), plan, control.page, session.getCurrentRevision(),
                action.vaultBefore(), action.vaultAfter());
        if (!prepared || closed.get()) {
            fail(control, control.mutationId, Failure.PREPARE);
            return;
        }
        if (control.abandoned) { park(control, control.mutationId); return; }
        mainExecutor.execute(() -> applySettlementPlayer(control, plan));
    }

    private void applySettlementPlayer(SessionControl control, MutationPlan plan) {
        if (closed.get()) return;
        if (control.abandoned) { park(control, control.mutationId); return; }
        ApplyResult result = views.applySettlementPlayer(control.view(), plan);
        if (result == ApplyResult.OK) {
            asyncExecutor.execute(() -> commitSettlement(control, plan));
        } else if (result == ApplyResult.STALE_VIEW) {
            asyncExecutor.execute(() -> {
                control.backend.abortSettlement(control.mutationId, plan.settlement().opSequence());
                fail(control, control.mutationId, Failure.STALE_VIEW);
            });
        } else if (result == ApplyResult.DIVERGED) {
            if (plan.settlement().kind() == CursorSettlement.Kind.FALLBACK) {
                asyncExecutor.execute(() -> {
                    control.backend.abortSettlement(control.mutationId, plan.settlement().opSequence());
                    park(control, control.mutationId);
                });
                return;
            }
            asyncExecutor.execute(() -> {
                control.backend.quarantine(control.mutationId);
                fail(control, control.mutationId, Failure.DIVERGED);
            });
        } else {
            fail(control, control.mutationId, Failure.RESERVE);
        }
    }

    private void commitSettlement(SessionControl control, MutationPlan plan) {
        NetworkSessionStore.SettlementResult result = control.backend.applySettlement(
                control.mutationId, plan.settlement().opSequence());
        if (!result.success() || closed.get()) {
            fail(control, control.mutationId, Failure.COMMIT);
            return;
        }
        if (control.abandoned) { park(control, control.mutationId); return; }
        PlannedMutation committed = new PlannedMutation(
                result.plan(), result.vaultBefore(), result.vaultAfter());
        mainExecutor.execute(() -> finishSettlementView(control, committed, result.newRevision()));
    }

    private void finishSettlementView(SessionControl control, PlannedMutation committed, long newRevision) {
        if (closed.get()) return;
        MutationPlan plan = committed.playerPlan();
        ApplyResult result = views.applySettlementCommitted(control.view(), committed, newRevision);
        if (result != ApplyResult.OK) {
            fail(control, control.mutationId, result == ApplyResult.DIVERGED
                    ? Failure.DIVERGED : Failure.APPLY);
            return;
        }
        asyncExecutor.execute(() -> {
            if (!control.backend.completeSettlement(control.mutationId, plan.settlement().opSequence())) {
                fail(control, control.mutationId, Failure.ACK);
                return;
            }
            CursorEscrow next = plan.settlement().nextEscrow();
            control.escrow = next;
            control.cursorDetached = false;
            control.operationPending = false;
            if (next == null) settle(control, control.mutationId);
        });
    }

    private void prepare(SessionControl control, MutationJournalRecord journal, PlannedMutation plan) {
        NetworkSessionStore.PrepareResult prepared = control.backend.prepare(journal);
        if (prepared != NetworkSessionStore.PrepareResult.PREPARED
                && prepared != NetworkSessionStore.PrepareResult.REPLAY) {
            fail(control, journal.mutationId(), Failure.PREPARE);
            return;
        }
        if (closed.get()) return;
        if (control.abandoned) {
            park(control, journal.mutationId());
            return;
        }
        mainExecutor.execute(() -> reserve(control, journal, plan));
    }

    private void reserve(SessionControl control, MutationJournalRecord journal, PlannedMutation plan) {
        if (closed.get()) return;
        if (control.abandoned) {
            park(control, journal.mutationId());
            return;
        }
        ApplyResult reserved = views.reserve(control.view(), plan);
        if (reserved == ApplyResult.OK) {
            asyncExecutor.execute(() -> commit(control, journal, plan));
        } else if (reserved == ApplyResult.STALE_VIEW) {
            asyncExecutor.execute(() -> {
                control.backend.abortPrepared(journal.mutationId());
                fail(control, journal.mutationId(), Failure.STALE_VIEW);
            });
        } else if (reserved == ApplyResult.DIVERGED) {
            asyncExecutor.execute(() -> {
                control.backend.quarantine(journal.mutationId());
                fail(control, journal.mutationId(), Failure.DIVERGED);
            });
        } else {
            // PREPARED remains durable. Recovery can distinguish BEFORE from RESERVED even when
            // the player disconnected or saveData failed after the in-memory reservation.
            fail(control, journal.mutationId(), Failure.RESERVE);
        }
    }

    private void commit(SessionControl control, MutationJournalRecord journal, PlannedMutation plan) {
        Storage.SaveResult result = control.backend.commitPrepared(journal.mutationId());
        if (closed.get()) return;
        if (control.abandoned) {
            park(control, journal.mutationId());
            return;
        }
        if (result instanceof Storage.SaveResult.Success success) {
            mainExecutor.execute(() -> applyCommitted(control, journal, plan, success.newRevision()));
        } else if (result instanceof Storage.SaveResult.Conflict) {
            mainExecutor.execute(() -> restoreConflict(control, journal, plan));
        } else {
            fail(control, journal.mutationId(), Failure.COMMIT);
        }
    }

    private void restoreConflict(SessionControl control, MutationJournalRecord journal, PlannedMutation plan) {
        if (closed.get()) return;
        if (control.abandoned) {
            park(control, journal.mutationId());
            return;
        }
        ApplyResult restored = views.restoreBefore(control.view(), plan);
        asyncExecutor.execute(() -> {
            if (restored == ApplyResult.OK && control.backend.abortPrepared(journal.mutationId())) {
                fail(control, journal.mutationId(), Failure.CONFLICT);
            } else {
                control.backend.quarantine(journal.mutationId());
                fail(control, journal.mutationId(), Failure.DIVERGED);
            }
        });
    }

    private void applyCommitted(
            SessionControl control, MutationJournalRecord journal, PlannedMutation plan, long newRevision) {
        if (closed.get()) return;
        if (control.abandoned) {
            park(control, journal.mutationId());
            return;
        }
        ApplyResult applied = views.applyCommitted(control.view(), plan, newRevision);
        if (applied == ApplyResult.DIVERGED) {
            asyncExecutor.execute(() -> {
                control.backend.quarantine(journal.mutationId());
                fail(control, journal.mutationId(), Failure.DIVERGED);
            });
            return;
        }
        if (applied != ApplyResult.OK) {
            // DB_COMMITTED remains recoverable; never rewrite ownership heuristically.
            fail(control, journal.mutationId(), Failure.APPLY);
            return;
        }
        if (plan.playerPlan().isCursorStable()) {
            control.escrow = plan.playerPlan().escrow();
            control.cursorDetached = false;
            control.operationPending = false;
            return;
        }
        asyncExecutor.execute(() -> {
            if (control.backend.acknowledge(journal.mutationId())) {
                settle(control, journal.mutationId());
            } else {
                fail(control, journal.mutationId(), Failure.ACK);
            }
        });
    }

    public void closeSession(UUID sessionId) {
        closeSession(sessionId, null);
    }

    public void closeSession(UUID sessionId, Runnable afterRelease) {
        SessionControl control = sessions.get(sessionId);
        if (control == null) {
            if (afterRelease != null && !closed.get()) mainExecutor.execute(afterRelease);
            return;
        }
        if (afterRelease != null) control.afterRelease.add(afterRelease);
        control.closeRequested = true;
        if (control.mutationId == null) release(control);
    }

    public void abandonSession(UUID sessionId) {
        SessionControl control = sessions.get(sessionId);
        if (control == null) return;
        control.abandoned = true;
        control.closeRequested = true;
        if (control.mutationId == null) release(control);
    }

    private void settle(SessionControl control, UUID mutationId) {
        ownerMutations.remove(control.ownerUuid, mutationId);
        if (mutationId.equals(control.mutationId)) control.mutationId = null;
        control.operationPending = false;
        if (control.closeRequested) release(control);
    }

    private void fail(SessionControl control, UUID mutationId, Failure failure) {
        if (closed.get()) return;
        if (control.abandoned) {
            park(control, mutationId);
            return;
        }
        ownerMutations.remove(control.ownerUuid, mutationId);
        if (mutationId.equals(control.mutationId)) control.mutationId = null;
        control.operationPending = false;
        control.closeRequested = true;
        mainExecutor.execute(() -> views.failClosed(control.view(), failure));
        release(control);
    }

    private void park(SessionControl control, UUID mutationId) {
        ownerMutations.remove(control.ownerUuid, mutationId);
        if (mutationId.equals(control.mutationId)) control.mutationId = null;
        control.operationPending = false;
        release(control);
    }

    private void release(SessionControl control) {
        if (!sessions.remove(control.sessionId, control)) return;
        asyncExecutor.execute(() -> {
            control.backend.release(control.sessionId);
            if (!closed.get() && !control.afterRelease.isEmpty()) {
                mainExecutor.execute(() -> {
                    Runnable callback;
                    while ((callback = control.afterRelease.poll()) != null) callback.run();
                });
            }
        });
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        for (SessionControl control : sessions.values()) control.closeRequested = true;
        sessions.clear();
        ownerMutations.clear();
    }

    public enum OpenResult { GRANTED, BUSY, RECOVERY_PENDING, QUARANTINED, UNAVAILABLE }
    public record OpenOutcome(OpenResult result, UUID sessionId, long fence) {
        public OpenOutcome {
            if ((result == OpenResult.GRANTED) != (sessionId != null && fence > 0)) {
                throw new IllegalArgumentException("open outcome is inconsistent");
            }
        }
        private static OpenOutcome failed(OpenResult result) { return new OpenOutcome(result, null, 0); }
    }
    public enum SubmitResult { ACCEPTED, BUSY, FROZEN, STALE }
    public enum ApplyResult { OK, STALE_VIEW, DIVERGED, OFFLINE, SAVE_FAILED }
    public enum Failure { PREPARE, RESERVE, STALE_VIEW, DIVERGED, COMMIT, CONFLICT, APPLY, ACK }

    public interface ViewPort {
        CursorEscrow createEscrow(UUID mutationId, long opSequence, SlotValue canonical,
                                  java.util.List<SlotMutation> fallback);
        ApplyResult reserve(ViewIdentity view, PlannedMutation plan);
        ApplyResult restoreBefore(ViewIdentity view, PlannedMutation plan);
        ApplyResult applyCommitted(ViewIdentity view, PlannedMutation plan, long newRevision);
        ApplyResult applySettlementPlayer(ViewIdentity view, MutationPlan plan);
        ApplyResult applySettlementCommitted(ViewIdentity view, PlannedMutation plan, long newRevision);
        void failClosed(ViewIdentity view, Failure failure);
    }

    public record ViewIdentity(UUID sessionId, UUID ownerUuid, UUID actorUuid,
                               int page, long actorRequest, long fence) {}

    private record OpenRequest(UUID sessionId, UUID ownerUuid, UUID actorUuid,
                               int page, long actorRequest) {}

    interface BackendProvider {
        Backend current();
    }

    interface Backend {
        RedisLeaseCoordinator.AcquireResult acquire(UUID ownerUuid, UUID sessionId);
        java.util.concurrent.CompletableFuture<CrossServerRecoveryService.Outcome> recover(UUID ownerUuid);
        boolean mayAcceptMutation(UUID sessionId);
        NetworkSessionStore.PrepareResult prepare(MutationJournalRecord journal);
        Storage.SaveResult commitPrepared(UUID mutationId);
        boolean acknowledge(UUID mutationId);
        boolean abortPrepared(UUID mutationId);
        boolean prepareSettlement(UUID mutationId, long expectedOpSequence, MutationPlan plan,
                                  int page, long baseRevision, String vaultBefore, String vaultAfter);
        boolean abortSettlement(UUID mutationId, long opSequence);
        NetworkSessionStore.SettlementResult applySettlement(UUID mutationId, long opSequence);
        boolean completeSettlement(UUID mutationId, long opSequence);
        void quarantine(UUID mutationId);
        boolean release(UUID sessionId);
    }

    private static final class RuntimeBackend implements Backend {
        private final NetworkRuntimeFactory.Runtime runtime;
        private final CrossServerRecoveryService recovery;

        private RuntimeBackend(NetworkRuntimeFactory.Runtime runtime,
                               CrossServerRecoveryService.PlayerDataPort playerData,
                               Executor asyncExecutor, Executor mainExecutor) {
            this.runtime = runtime;
            this.recovery = new CrossServerRecoveryService(
                    runtime.coordinator(), playerData, asyncExecutor, mainExecutor);
        }

        @Override public RedisLeaseCoordinator.AcquireResult acquire(UUID ownerUuid, UUID sessionId) {
            return runtime.coordinator().acquire(ownerUuid, sessionId);
        }
        @Override public java.util.concurrent.CompletableFuture<CrossServerRecoveryService.Outcome> recover(UUID ownerUuid) {
            return recovery.recover(ownerUuid);
        }
        @Override public boolean mayAcceptMutation(UUID sessionId) {
            return runtime.coordinator().mayAcceptMutation(sessionId);
        }
        @Override public NetworkSessionStore.PrepareResult prepare(MutationJournalRecord journal) {
            return runtime.sessions().prepare(journal);
        }
        @Override public Storage.SaveResult commitPrepared(UUID mutationId) {
            return runtime.sessions().commitPrepared(mutationId);
        }
        @Override public boolean acknowledge(UUID mutationId) { return runtime.sessions().acknowledge(mutationId); }
        @Override public boolean abortPrepared(UUID mutationId) { return runtime.sessions().abortPrepared(mutationId); }
        @Override public boolean prepareSettlement(UUID mutationId, long expectedOpSequence,
                                                   MutationPlan plan, int page, long baseRevision,
                                                   String vaultBefore, String vaultAfter) {
            return runtime.sessions().prepareSettlement(mutationId, expectedOpSequence, plan,
                    page, baseRevision, vaultBefore, vaultAfter);
        }
        @Override public boolean abortSettlement(UUID mutationId, long opSequence) {
            return runtime.sessions().abortSettlement(mutationId, opSequence);
        }
        @Override public NetworkSessionStore.SettlementResult applySettlement(UUID mutationId, long opSequence) {
            return runtime.sessions().applySettlement(mutationId, opSequence);
        }
        @Override public boolean completeSettlement(UUID mutationId, long opSequence) {
            return runtime.sessions().completeSettlement(mutationId, opSequence);
        }
        @Override public void quarantine(UUID mutationId) { runtime.sessions().quarantine(mutationId); }
        @Override public boolean release(UUID sessionId) { return runtime.coordinator().release(sessionId); }
    }

    private static final class SessionControl {
        private final UUID sessionId;
        private final UUID ownerUuid;
        private final UUID actorUuid;
        private volatile int page;
        private volatile long actorRequest;
        private final long fence;
        private final Backend backend;
        private volatile UUID mutationId;
        private volatile CursorEscrow escrow;
        private volatile boolean operationPending;
        private volatile boolean cursorDetached;
        private volatile boolean closeRequested;
        private volatile boolean abandoned;
        private long mutationSequence;
        private final Queue<Runnable> afterRelease = new ConcurrentLinkedQueue<>();

        private SessionControl(UUID sessionId, UUID ownerUuid, UUID actorUuid, int page,
                               long actorRequest, long fence, Backend backend) {
            this.sessionId = sessionId;
            this.ownerUuid = ownerUuid;
            this.actorUuid = actorUuid;
            this.page = page;
            this.actorRequest = actorRequest;
            this.fence = fence;
            this.backend = backend;
        }

        private ViewIdentity view() {
            return new ViewIdentity(sessionId, ownerUuid, actorUuid, page, actorRequest, fence);
        }
    }
}
