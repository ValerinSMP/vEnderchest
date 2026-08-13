package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.session.OpenAttempt;
import com.valerin.venderchest.session.VaultSession;
import com.valerin.venderchest.session.VaultSessionRegistry;
import com.valerin.venderchest.storage.NetworkSessionStore;
import com.valerin.venderchest.storage.Storage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerMutationControllerTest {

    @Test
    void cursorEscrowStaysActiveAcrossClicksAndAcksOnlyAfterPersistedSettlement() {
        Fixture fixture = new Fixture();
        fixture.openAndActivate();
        SlotValue item = SlotValue.fromText("sword");
        var pickup = new CrossServerInventoryPlanner.CursorActionPlan(
                List.of(), SlotValue.empty(), item, "vault-before", "vault-after");

        assertEquals(CrossServerMutationController.SubmitResult.ACCEPTED,
                fixture.controller.submitCursor(fixture.session, pickup));
        fixture.drain();

        CursorEscrow stable = fixture.controller.cursorEscrow(fixture.session.getSessionId());
        assertEquals(item, stable.canonical());
        assertEquals(1, stable.opSequence());
        assertEquals(0, fixture.backend.acks);
        assertFalse(fixture.controller.hasInFlight(fixture.session.getSessionId()));

        SlotMutation destination = new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, 4),
                SlotValue.empty(), SlotValue.empty(), item);
        var place = new CrossServerInventoryPlanner.CursorActionPlan(
                List.of(destination), item, SlotValue.empty(), "vault-after", "vault-after");
        assertEquals(CrossServerMutationController.SubmitResult.ACCEPTED,
                fixture.controller.submitCursor(fixture.session, place));
        fixture.drain();

        assertEquals(1, fixture.backend.settlementPrepares);
        assertEquals(1, fixture.backend.settlementApplies);
        assertEquals(1, fixture.backend.settlementCompletes);
        assertNull(fixture.controller.cursorEscrow(fixture.session.getSessionId()));
        assertFalse(fixture.controller.hasInFlight(fixture.session.getSessionId()));
    }


    @Test
    void openAndMutationAdvanceOnlyOnTheirAssignedExecutors() {
        Fixture fixture = new Fixture();
        AtomicReference<CrossServerMutationController.OpenOutcome> opened = new AtomicReference<>();

        fixture.controller.prepareOpen(fixture.owner, fixture.actor, 2, 4, opened::set);
        assertNull(opened.get());
        fixture.async.runOne();
        assertNull(opened.get());
        fixture.main.runOne();
        assertEquals(CrossServerMutationController.OpenResult.GRANTED, opened.get().result());
        fixture.activate(opened.get());

        assertEquals(CrossServerMutationController.SubmitResult.ACCEPTED,
                fixture.controller.submit(fixture.session, fixture.plan));
        assertEquals(CrossServerMutationController.SubmitResult.BUSY,
                fixture.controller.submit(fixture.session, fixture.plan));
        assertEquals(0, fixture.views.reserves);

        fixture.drain();

        assertEquals(1, fixture.backend.prepares);
        assertEquals(1, fixture.views.reserves);
        assertEquals(1, fixture.backend.commits);
        assertEquals(1, fixture.views.applies);
        assertEquals(1, fixture.backend.acks);
        assertFalse(fixture.controller.hasInFlight(fixture.session.getSessionId()));
        assertEquals(0, fixture.views.failures);
    }

    @Test
    void closeDuringPreparedDoesNotDiscardMutationAndReleasesOnlyAfterAck() {
        Fixture fixture = new Fixture();
        fixture.openAndActivate();
        assertEquals(CrossServerMutationController.SubmitResult.ACCEPTED,
                fixture.controller.submit(fixture.session, fixture.plan));

        fixture.async.runOne(); // PREPARED durable, reserve scheduled on main
        fixture.controller.closeSession(fixture.session.getSessionId());
        assertEquals(0, fixture.backend.releases);

        fixture.drain();

        assertEquals(1, fixture.backend.acks);
        assertEquals(1, fixture.backend.releases);
        assertFalse(fixture.controller.isTracked(fixture.session.getSessionId()));
    }

    @Test
    void conflictRestoresReservedPlayerStateBeforeDurableAbort() {
        Fixture fixture = new Fixture();
        fixture.backend.commitResult = new Storage.SaveResult.Conflict(12);
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.views.restores);
        assertEquals(1, fixture.backend.aborts);
        assertEquals(1, fixture.views.failures);
        assertEquals(CrossServerMutationController.Failure.CONFLICT, fixture.views.lastFailure);
        assertEquals(1, fixture.backend.releases);
    }

    @Test
    void staleViewAbortsPreparedWithoutCommitOrPlayerCompensation() {
        Fixture fixture = new Fixture();
        fixture.views.reserveResult = CrossServerMutationController.ApplyResult.STALE_VIEW;
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.backend.prepares);
        assertEquals(0, fixture.backend.commits);
        assertEquals(1, fixture.backend.aborts);
        assertEquals(0, fixture.views.restores);
        assertEquals(1, fixture.backend.releases);
    }

    @Test
    void leaseLossBeforeAdmissionFreezesWithoutJournalButAfterReserveDefersToFencedCommit() {
        Fixture before = new Fixture();
        before.openAndActivate();
        before.backend.fresh = false;
        assertEquals(CrossServerMutationController.SubmitResult.FROZEN,
                before.controller.submit(before.session, before.plan));
        assertEquals(0, before.backend.prepares);

        Fixture after = new Fixture();
        after.openAndActivate();
        after.controller.submit(after.session, after.plan);
        after.async.runOne();
        after.main.runOne();
        after.backend.fresh = false;
        after.drain();
        assertEquals(1, after.backend.commits);
        assertEquals(1, after.backend.acks);
    }

    @Test
    void saveFailureAfterReserveLeavesPreparedForRecoveryInsteadOfQuarantine() {
        Fixture fixture = new Fixture();
        fixture.views.reserveResult = CrossServerMutationController.ApplyResult.SAVE_FAILED;
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.backend.prepares);
        assertEquals(0, fixture.backend.commits);
        assertEquals(0, fixture.backend.quarantines);
        assertEquals(CrossServerMutationController.Failure.RESERVE, fixture.views.lastFailure);
    }

    @Test
    void closeOrNewPageAfterDatabaseCommitLeavesCommittedJournalRecoverable() {
        Fixture fixture = new Fixture();
        fixture.views.applyResult = CrossServerMutationController.ApplyResult.STALE_VIEW;
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.backend.commits);
        assertEquals(0, fixture.backend.acks);
        assertEquals(0, fixture.backend.quarantines);
        assertEquals(CrossServerMutationController.Failure.APPLY, fixture.views.lastFailure);
        assertEquals(1, fixture.backend.releases);
    }

    @Test
    void pageSwitchRebindsSameLeaseWithoutAcquireOrRelease() {
        Fixture fixture = new Fixture();
        fixture.openAndActivate();

        assertTrue(fixture.controller.rebindView(fixture.session.getSessionId(), 3, 9));
        assertEquals(1, fixture.backend.acquires);
        assertEquals(0, fixture.backend.releases);
        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(3, fixture.backend.lastJournal.page());
        assertEquals(fixture.session.getSessionId(), fixture.backend.lastJournal.sessionId());
        fixture.controller.closeSession(fixture.session.getSessionId());
        fixture.drain();
        assertEquals(1, fixture.backend.releases);
    }

    @Test
    void normalCloseWithoutMutationReleasesExactlyOnce() {
        Fixture fixture = new Fixture();
        fixture.openAndActivate();

        fixture.controller.closeSession(fixture.session.getSessionId());
        fixture.controller.closeSession(fixture.session.getSessionId());
        fixture.drain();

        assertEquals(1, fixture.backend.releases);
    }

    @Test
    void closeAfterCommitAndBeforeAckKeepsLeaseUntilAckIsDurable() {
        Fixture fixture = new Fixture();
        fixture.openAndActivate();
        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.async.runOne(); // PREPARED
        fixture.main.runOne();  // reserve + saveData
        fixture.async.runOne(); // DB_COMMITTED, apply scheduled
        fixture.main.runOne();  // AFTER + saveData, ACK scheduled

        fixture.controller.closeSession(fixture.session.getSessionId());
        assertEquals(0, fixture.backend.releases);
        fixture.async.runOne(); // ACK; release scheduled
        assertEquals(0, fixture.backend.releases);
        fixture.async.runOne(); // release
        assertEquals(1, fixture.backend.releases);
    }

    @Test
    void quitDuringPreparedOrCommittedParksJournalWithoutLateViewCallback() {
        Fixture prepared = new Fixture();
        prepared.openAndActivate();
        prepared.controller.submit(prepared.session, prepared.plan);
        prepared.async.runOne(); // PREPARED; reserve queued
        prepared.controller.abandonSession(prepared.session.getSessionId());
        prepared.drain();
        assertEquals(0, prepared.views.reserves);
        assertEquals(0, prepared.views.failures);
        assertEquals(1, prepared.backend.releases);

        Fixture committed = new Fixture();
        committed.openAndActivate();
        committed.controller.submit(committed.session, committed.plan);
        committed.async.runOne();
        committed.main.runOne();
        committed.controller.abandonSession(committed.session.getSessionId());
        committed.async.runOne(); // DB may commit, but no apply callback is scheduled
        committed.drain();
        assertEquals(0, committed.views.applies);
        assertEquals(0, committed.views.failures);
        assertEquals(1, committed.backend.releases);
    }

    @Test
    void shutdownAfterSubmitDoesNotScheduleAnyMainCallback() {
        Fixture fixture = new Fixture();
        fixture.openAndActivate();
        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.controller.shutdown();

        fixture.async.runOne();

        assertTrue(fixture.main.isEmpty());
        assertEquals(0, fixture.views.reserves);
        assertEquals(0, fixture.views.failures);
    }

    @Test
    void reserveFingerprintDivergenceQuarantinesWithoutRestoreOrCommit() {
        Fixture fixture = new Fixture();
        fixture.views.reserveResult = CrossServerMutationController.ApplyResult.DIVERGED;
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.backend.quarantines);
        assertEquals(0, fixture.backend.commits);
        assertEquals(0, fixture.views.restores);
        assertEquals(0, fixture.views.applies);
    }

    @Test
    void failedSaveAfterCommittedApplyLeavesJournalPending() {
        Fixture fixture = new Fixture();
        fixture.views.applyResult = CrossServerMutationController.ApplyResult.SAVE_FAILED;
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.backend.commits);
        assertEquals(0, fixture.backend.acks);
        assertEquals(0, fixture.backend.quarantines);
        assertEquals(CrossServerMutationController.Failure.APPLY, fixture.views.lastFailure);
    }

    @Test
    void newerFenceAtCommitRejectsStaleWriterWithoutApplyOrAck() {
        Fixture fixture = new Fixture();
        fixture.backend.commitResult = new Storage.SaveResult.StaleFence(8);
        fixture.openAndActivate();

        fixture.controller.submit(fixture.session, fixture.plan);
        fixture.drain();

        assertEquals(1, fixture.backend.commits);
        assertEquals(0, fixture.views.applies);
        assertEquals(0, fixture.backend.acks);
        assertEquals(CrossServerMutationController.Failure.COMMIT, fixture.views.lastFailure);
        assertEquals(1, fixture.backend.releases);
    }

    private static final class Fixture {
        private final UUID owner = UUID.randomUUID();
        private final UUID actor = UUID.randomUUID();
        private final VaultSessionRegistry registry = new VaultSessionRegistry();
        private VaultSession session;
        private CrossServerMutationController.OpenOutcome grant;
        private final QueueExecutor async = new QueueExecutor();
        private final QueueExecutor main = new QueueExecutor();
        private final FakeBackend backend = new FakeBackend();
        private final FakeViews views = new FakeViews();
        private final CrossServerMutationController controller = new CrossServerMutationController(
                () -> CrossServerLifecycle.State.ACTIVE,
                () -> backend, views, async, main);
        private final PlannedMutation plan = new PlannedMutation(
                new MutationPlan(List.of(new SlotMutation(
                        new SlotRef(SlotRef.Area.PLAYER, 4), SlotValue.fromText("before"),
                        SlotValue.empty(), SlotValue.fromText("after")))), "vault-before", "vault-after");

        void openAndActivate() {
            controller.prepareOpen(owner, actor, 2, 4, outcome -> grant = outcome);
            drain();
            activate(grant);
        }

        void activate(CrossServerMutationController.OpenOutcome outcome) {
            session = ((OpenAttempt.Created) registry.beginOpenCrossServer(
                    owner, actor, "2", outcome.sessionId(), outcome.fence())).session();
            assertTrue(registry.activate(session.getSessionId(), 11, new Object()));
        }

        void drain() {
            while (!async.isEmpty() || !main.isEmpty()) {
                async.drain();
                main.drain();
            }
        }
    }

    private static final class FakeBackend implements CrossServerMutationController.Backend {
        private final long fence = 7;
        private boolean fresh = true;
        private Storage.SaveResult commitResult = new Storage.SaveResult.Success(12);
        private int prepares;
        private int commits;
        private int acks;
        private int aborts;
        private int releases;
        private int quarantines;
        private int acquires;
        private MutationJournalRecord lastJournal;
        private MutationPlan settlementPlan;
        private String settlementBefore;
        private String settlementAfter;
        private int settlementPrepares;
        private int settlementApplies;
        private int settlementCompletes;

        @Override public RedisLeaseCoordinator.AcquireResult acquire(UUID ownerUuid, UUID sessionId) {
            acquires++;
            return new RedisLeaseCoordinator.AcquireResult.Granted(fence);
        }
        @Override public CompletableFuture<CrossServerRecoveryService.Outcome> recover(UUID ownerUuid) {
            return CompletableFuture.completedFuture(CrossServerRecoveryService.Outcome.CLEAR);
        }
        @Override public boolean mayAcceptMutation(UUID sessionId) { return fresh; }
        @Override public NetworkSessionStore.PrepareResult prepare(MutationJournalRecord journal) {
            prepares++;
            lastJournal = journal;
            return NetworkSessionStore.PrepareResult.PREPARED;
        }
        @Override public Storage.SaveResult commitPrepared(UUID mutationId) { commits++; return commitResult; }
        @Override public boolean acknowledge(UUID mutationId) { acks++; return true; }
        @Override public boolean abortPrepared(UUID mutationId) { aborts++; return true; }
        @Override public boolean prepareSettlement(UUID mutationId, long expectedOpSequence,
                                                   MutationPlan plan, int page, long baseRevision,
                                                   String vaultBefore, String vaultAfter) {
            settlementPrepares++;
            settlementPlan = plan;
            settlementBefore = vaultBefore;
            settlementAfter = vaultAfter;
            return true;
        }
        @Override public boolean abortSettlement(UUID mutationId, long opSequence) { return true; }
        @Override public NetworkSessionStore.SettlementResult applySettlement(UUID mutationId, long opSequence) {
            settlementApplies++;
            CursorSettlement current = settlementPlan.settlement();
            CursorSettlement applied = new CursorSettlement(current.kind(),
                    CursorSettlement.Stage.VAULT_APPLIED, current.opSequence(),
                    current.cursorBefore(), current.cursorAfter(), current.nextEscrow());
            MutationPlan appliedPlan = MutationPlan.settlement(
                    settlementPlan.playerSlots(), settlementPlan.escrow(), applied);
            return new NetworkSessionStore.SettlementResult(
                    true, 13, appliedPlan, settlementBefore, settlementAfter);
        }
        @Override public boolean completeSettlement(UUID mutationId, long opSequence) {
            settlementCompletes++;
            return true;
        }
        @Override public void quarantine(UUID mutationId) { quarantines++; }
        @Override public boolean release(UUID sessionId) { releases++; return true; }
    }

    private static final class FakeViews implements CrossServerMutationController.ViewPort {
        private CrossServerMutationController.ApplyResult reserveResult = CrossServerMutationController.ApplyResult.OK;
        private CrossServerMutationController.ApplyResult applyResult = CrossServerMutationController.ApplyResult.OK;
        private int reserves;
        private int restores;
        private int applies;
        private int failures;
        private CrossServerMutationController.Failure lastFailure;

        @Override public CursorEscrow createEscrow(UUID mutationId, long opSequence,
                                                   SlotValue canonical, List<SlotMutation> fallback) {
            return new CursorEscrow(mutationId, opSequence, canonical,
                    SlotValue.fromText("projection-" + opSequence), fallback);
        }

        @Override public CrossServerMutationController.ApplyResult reserve(
                CrossServerMutationController.ViewIdentity view, PlannedMutation plan) {
            reserves++;
            return reserveResult;
        }
        @Override public CrossServerMutationController.ApplyResult restoreBefore(
                CrossServerMutationController.ViewIdentity view, PlannedMutation plan) {
            restores++;
            return CrossServerMutationController.ApplyResult.OK;
        }
        @Override public CrossServerMutationController.ApplyResult applyCommitted(
                CrossServerMutationController.ViewIdentity view, PlannedMutation plan, long newRevision) {
            applies++;
            return applyResult;
        }
        @Override public CrossServerMutationController.ApplyResult applySettlementPlayer(
                CrossServerMutationController.ViewIdentity view, MutationPlan plan) {
            return applyResult;
        }
        @Override public CrossServerMutationController.ApplyResult applySettlementCommitted(
                CrossServerMutationController.ViewIdentity view, PlannedMutation plan, long newRevision) {
            return applyResult;
        }
        @Override public void failClosed(
                CrossServerMutationController.ViewIdentity view, CrossServerMutationController.Failure failure) {
            failures++;
            lastFailure = failure;
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        boolean isEmpty() { return tasks.isEmpty(); }
        void runOne() { tasks.remove().run(); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
