package com.valerin.venderchest.crossserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerRecoveryServiceTest {

    @Test
    void preparedBeforeAbortsWithoutTouchingPlayerdata() {
        Fixture fixture = fixture(MutationState.PREPARED, MutationPlan.Phase.BEFORE);

        var future = fixture.service.recover(fixture.journal.ownerUuid());
        fixture.drain();

        assertEquals(CrossServerRecoveryService.Outcome.RECOVERED, future.join());
        assertTrue(fixture.coordinator.aborted);
        assertEquals(0, fixture.player.saves);
        assertEquals(MutationPlan.Phase.BEFORE, fixture.player.phase(fixture.journal.playerPlan()));
    }

    @Test
    void preparedReservedRestoresBeforeAndSavesBeforeAbort() {
        Fixture fixture = fixture(MutationState.PREPARED, MutationPlan.Phase.RESERVED);

        var future = fixture.service.recover(fixture.journal.ownerUuid());
        fixture.drain();

        assertEquals(CrossServerRecoveryService.Outcome.RECOVERED, future.join());
        assertTrue(fixture.coordinator.aborted);
        assertEquals(1, fixture.player.saves);
        assertEquals(MutationPlan.Phase.BEFORE, fixture.player.phase(fixture.journal.playerPlan()));
    }

    @Test
    void committedBeforeOrReservedAppliesAfterAndAckReplayNeedsNoSecondSave() {
        Fixture before = fixture(MutationState.DB_COMMITTED, MutationPlan.Phase.BEFORE);
        var first = before.service.recover(before.journal.ownerUuid());
        before.drain();
        assertEquals(CrossServerRecoveryService.Outcome.RECOVERED, first.join());
        assertEquals(MutationPlan.Phase.AFTER, before.player.phase(before.journal.playerPlan()));
        assertTrue(before.coordinator.acknowledged);
        assertEquals(1, before.player.saves);

        Fixture after = fixture(MutationState.DB_COMMITTED, MutationPlan.Phase.AFTER);
        var replay = after.service.recover(after.journal.ownerUuid());
        after.drain();
        assertEquals(CrossServerRecoveryService.Outcome.RECOVERED, replay.join());
        assertEquals(0, after.player.saves);
        assertTrue(after.coordinator.acknowledged);
    }

    @Test
    void thirdPartyChangeBeforeApplyQuarantinesWithoutPartialCompensation() {
        Fixture fixture = fixture(MutationState.DB_COMMITTED, MutationPlan.Phase.BEFORE);
        Map<SlotRef, SlotValue> original = Map.copyOf(fixture.player.slots);
        fixture.player.divergeBeforeApply = true;

        var future = fixture.service.recover(fixture.journal.ownerUuid());
        fixture.drain();

        assertEquals(CrossServerRecoveryService.Outcome.QUARANTINED, future.join());
        assertTrue(fixture.coordinator.quarantined);
        assertEquals(0, fixture.player.saves);
        assertFalse(fixture.player.slots.equals(original));
        assertFalse(fixture.coordinator.releasedWithPause);
    }

    @Test
    void disconnectAndSaveFailureLeaveJournalPending() {
        Fixture offline = fixture(MutationState.PREPARED, MutationPlan.Phase.RESERVED);
        offline.player.online = false;
        var disconnected = offline.service.recover(offline.journal.ownerUuid());
        offline.drain();
        assertEquals(CrossServerRecoveryService.Outcome.OFFLINE_PENDING, disconnected.join());
        assertTrue(offline.coordinator.releasedWithPause);
        assertFalse(offline.coordinator.aborted);

        Fixture failedSave = fixture(MutationState.DB_COMMITTED, MutationPlan.Phase.BEFORE);
        failedSave.player.failSave = true;
        var pending = failedSave.service.recover(failedSave.journal.ownerUuid());
        failedSave.drain();
        assertEquals(CrossServerRecoveryService.Outcome.PENDING_RETRY, pending.join());
        assertFalse(failedSave.coordinator.acknowledged);
        assertEquals(MutationPlan.Phase.AFTER, failedSave.player.phase(failedSave.journal.playerPlan()));
    }

    @Test
    void lostAckIsIdempotentlyRecoverableFromAfterState() {
        Fixture fixture = fixture(MutationState.DB_COMMITTED, MutationPlan.Phase.RESERVED);
        fixture.coordinator.persistTerminal = false;

        var first = fixture.service.recover(fixture.journal.ownerUuid());
        fixture.drain();

        assertEquals(CrossServerRecoveryService.Outcome.PENDING_RETRY, first.join());
        assertEquals(MutationPlan.Phase.AFTER, fixture.player.phase(fixture.journal.playerPlan()));
        assertEquals(1, fixture.player.saves);
    }

    @Test
    void stableCursorWritesSettlementBeforePlayerdataThenAppliesVaultAndAcks() {
        UUID mutation = UUID.randomUUID();
        CursorEscrow escrow = new CursorEscrow(mutation, 4, SlotValue.fromText("item"),
                SlotValue.fromText("tagged-item"), List.of());
        SlotMutation destination = new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, 2),
                SlotValue.empty(), SlotValue.empty(), escrow.canonical());
        CursorSettlement settlement = new CursorSettlement(CursorSettlement.Kind.FALLBACK,
                CursorSettlement.Stage.PLANNED, 5, escrow.projection(), SlotValue.empty(), null);
        MutationPlan recoveryPlan = MutationPlan.settlement(List.of(destination), escrow, settlement);
        MutationJournalRecord journal = new MutationJournalRecord(mutation, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 8, 2, 10, 10L,
                MutationState.CURSOR_STABLE, MutationPlan.cursorStable(escrow),
                "vault", "vault");
        FakePlayerData player = new FakePlayerData();
        player.recoveryPlan = recoveryPlan;
        player.setPhase(recoveryPlan, MutationPlan.Phase.BEFORE);
        FakeCoordinator coordinator = new FakeCoordinator(journal);
        QueueExecutor async = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        CrossServerRecoveryService service = new CrossServerRecoveryService(
                coordinator, player, async, main, UUID::randomUUID);

        var future = service.recover(journal.ownerUuid());
        while (!async.tasks.isEmpty() || !main.tasks.isEmpty()) { async.drain(); main.drain(); }

        assertEquals(CrossServerRecoveryService.Outcome.RECOVERED, future.join());
        assertTrue(coordinator.settlementPrepared);
        assertTrue(coordinator.settlementApplied);
        assertTrue(coordinator.settlementCompleted);
        assertEquals(MutationPlan.Phase.AFTER, player.phase(recoveryPlan));
        assertEquals(1, player.saves);
    }

    @Test
    void recoveryWithCursorRemainderPublishesNextStableInsteadOfAck() {
        UUID mutation = UUID.randomUUID();
        CursorEscrow old = new CursorEscrow(mutation, 2, SlotValue.fromText("old"),
                SlotValue.fromText("old-projection"), List.of());
        CursorEscrow next = new CursorEscrow(mutation, 3, SlotValue.fromText("remaining"),
                SlotValue.fromText("next-projection"), List.of());
        SlotMutation player = new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, 1),
                SlotValue.empty(), SlotValue.empty(), SlotValue.fromText("placed"));
        CursorSettlement applied = new CursorSettlement(CursorSettlement.Kind.CURSOR_PLAYER_SWAP,
                CursorSettlement.Stage.VAULT_APPLIED, 3, old.projection(), next.projection(), next);
        MutationPlan plan = MutationPlan.settlement(List.of(player), old, applied);
        MutationJournalRecord journal = new MutationJournalRecord(mutation, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 9, 1, 4, 4L,
                MutationState.SETTLEMENT_PREPARED, plan, "vault", "vault");
        FakePlayerData playerData = new FakePlayerData();
        playerData.setPhase(plan, MutationPlan.Phase.AFTER);
        FakeCoordinator coordinator = new FakeCoordinator(journal);
        QueueExecutor async = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        CrossServerRecoveryService service = new CrossServerRecoveryService(
                coordinator, playerData, async, main, UUID::randomUUID);

        var future = service.recover(journal.ownerUuid());
        while (!async.tasks.isEmpty() || !main.tasks.isEmpty()) { async.drain(); main.drain(); }

        assertEquals(CrossServerRecoveryService.Outcome.PENDING_RETRY, future.join());
        assertTrue(coordinator.settlementCompleted);
        assertTrue(coordinator.releasedWithPause);
    }

    private Fixture fixture(MutationState state, MutationPlan.Phase phase) {
        SlotRef first = new SlotRef(SlotRef.Area.PLAYER, 3);
        SlotRef second = new SlotRef(SlotRef.Area.PLAYER, 8);
        MutationPlan plan = new MutationPlan(List.of(
                new SlotMutation(first, SlotValue.fromText("before-1"), SlotValue.empty(), SlotValue.fromText("after-1")),
                new SlotMutation(second, SlotValue.fromText("before-2"), SlotValue.fromText("reserved-2"), SlotValue.empty())
        ));
        MutationJournalRecord journal = new MutationJournalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 8, 2, 10, state == MutationState.DB_COMMITTED ? 11L : null,
                state, plan, "vault-before", "vault-after");
        FakePlayerData player = new FakePlayerData();
        player.setPhase(plan, phase);
        FakeCoordinator coordinator = new FakeCoordinator(journal);
        QueueExecutor async = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        CrossServerRecoveryService service = new CrossServerRecoveryService(
                coordinator, player, async, main, () -> UUID.fromString("00000000-0000-0000-0000-000000000099"));
        return new Fixture(journal, player, coordinator, async, main, service);
    }

    private record Fixture(
            MutationJournalRecord journal,
            FakePlayerData player,
            FakeCoordinator coordinator,
            QueueExecutor async,
            QueueExecutor main,
            CrossServerRecoveryService service
    ) {
        void drain() {
            while (!async.tasks.isEmpty() || !main.tasks.isEmpty()) {
                async.drain();
                main.drain();
            }
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    private static final class FakeCoordinator implements CrossServerRecoveryService.Coordinator {
        private final MutationJournalRecord journal;
        private boolean acknowledged;
        private boolean aborted;
        private boolean quarantined;
        private boolean releasedWithPause;
        private boolean persistTerminal = true;
        private boolean settlementPrepared;
        private boolean settlementApplied;
        private boolean settlementCompleted;

        private FakeCoordinator(MutationJournalRecord journal) { this.journal = journal; }
        @Override public RedisLeaseCoordinator.RecoveryAcquireResult acquireRecovery(UUID owner, UUID recovery) {
            return new RedisLeaseCoordinator.RecoveryAcquireResult.Acquired(journal);
        }
        @Override public boolean acknowledgeRecovery(UUID recovery, UUID mutation) {
            acknowledged = persistTerminal;
            return persistTerminal;
        }
        @Override public boolean abortRecovery(UUID recovery, UUID mutation) {
            aborted = persistTerminal;
            return persistTerminal;
        }
        @Override public boolean quarantineRecovery(UUID recovery, UUID mutation) {
            quarantined = persistTerminal;
            return persistTerminal;
        }
        @Override public boolean prepareSettlementRecovery(UUID recovery, UUID mutation, MutationPlan plan) {
            settlementPrepared = true;
            return persistTerminal;
        }
        @Override public boolean abortSettlementRecovery(UUID recovery, UUID mutation, long opSequence) {
            return persistTerminal;
        }
        @Override public boolean completeSettlementRecovery(UUID recovery, UUID mutation) {
            settlementCompleted = true;
            acknowledged = persistTerminal;
            return persistTerminal;
        }
        @Override public boolean applySettlementRecovery(UUID recovery, UUID mutation, long opSequence) {
            settlementApplied = true;
            return persistTerminal;
        }
        @Override public boolean releaseRecovery(UUID recovery, boolean pause) {
            releasedWithPause = pause;
            return true;
        }
    }

    private static final class FakePlayerData implements CrossServerRecoveryService.PlayerDataPort {
        private final Map<SlotRef, SlotValue> slots = new HashMap<>();
        private boolean online = true;
        private boolean divergeBeforeApply;
        private boolean failSave;
        private int saves;
        private MutationPlan recoveryPlan;

        void setPhase(MutationPlan plan, MutationPlan.Phase phase) {
            slots.clear();
            for (SlotMutation mutation : plan.playerSlots()) slots.put(mutation.slot(), value(mutation, phase));
        }

        MutationPlan.Phase phase(MutationPlan plan) {
            for (MutationPlan.Phase phase : MutationPlan.Phase.values()) {
                if (plan.matches(phase, slots)) return phase;
            }
            return null;
        }

        @Override public boolean isOnline(UUID actorUuid) { return online; }
        @Override public Map<SlotRef, SlotValue> snapshot(UUID actorUuid, MutationPlan plan) {
            return Map.copyOf(slots);
        }
        @Override public boolean compareAndApply(UUID actorUuid, MutationPlan plan,
                                                 Map<SlotRef, SlotValue> expected, MutationPlan.Phase target) {
            if (divergeBeforeApply) slots.put(plan.playerSlots().getFirst().slot(), SlotValue.fromText("third-party"));
            if (!slots.equals(expected)) return false;
            setPhase(plan, target);
            return true;
        }
        @Override public void saveData(UUID actorUuid) throws Exception {
            saves++;
            if (failSave) throw new Exception("save failed");
        }
        @Override public MutationPlan planEscrowSettlement(UUID actorUuid, CursorEscrow escrow) {
            return recoveryPlan;
        }

        private SlotValue value(SlotMutation mutation, MutationPlan.Phase phase) {
            return switch (phase) {
                case BEFORE -> mutation.before();
                case RESERVED -> mutation.reserved();
                case AFTER -> mutation.after();
            };
        }
    }
}
