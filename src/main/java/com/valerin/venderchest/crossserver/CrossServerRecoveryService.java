package com.valerin.venderchest.crossserver;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class CrossServerRecoveryService {

    private final Coordinator coordinator;
    private final PlayerDataPort playerData;
    private final Executor asyncExecutor;
    private final Executor mainExecutor;
    private final Supplier<UUID> recoveryIds;

    public CrossServerRecoveryService(
            Coordinator coordinator,
            PlayerDataPort playerData,
            Executor asyncExecutor,
            Executor mainExecutor
    ) {
        this(coordinator, playerData, asyncExecutor, mainExecutor, UUID::randomUUID);
    }

    CrossServerRecoveryService(
            Coordinator coordinator,
            PlayerDataPort playerData,
            Executor asyncExecutor,
            Executor mainExecutor,
            Supplier<UUID> recoveryIds
    ) {
        this.coordinator = coordinator;
        this.playerData = playerData;
        this.asyncExecutor = asyncExecutor;
        this.mainExecutor = mainExecutor;
        this.recoveryIds = recoveryIds;
    }

    public CompletableFuture<Outcome> recover(UUID ownerUuid) {
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        UUID recoveryId = recoveryIds.get();
        asyncExecutor.execute(() -> acquire(ownerUuid, recoveryId, result));
        return result;
    }

    private void acquire(UUID ownerUuid, UUID recoveryId, CompletableFuture<Outcome> result) {
        RedisLeaseCoordinator.RecoveryAcquireResult acquired;
        try {
            acquired = coordinator.acquireRecovery(ownerUuid, recoveryId);
        } catch (RuntimeException error) {
            result.complete(Outcome.PROVIDER_UNAVAILABLE);
            return;
        }
        if (acquired instanceof RedisLeaseCoordinator.RecoveryAcquireResult.None) {
            result.complete(Outcome.CLEAR);
        } else if (acquired instanceof RedisLeaseCoordinator.RecoveryAcquireResult.Busy) {
            result.complete(Outcome.BUSY);
        } else if (acquired instanceof RedisLeaseCoordinator.RecoveryAcquireResult.Quarantined) {
            result.complete(Outcome.QUARANTINED);
        } else if (acquired instanceof RedisLeaseCoordinator.RecoveryAcquireResult.Unavailable) {
            result.complete(Outcome.PROVIDER_UNAVAILABLE);
        } else {
            MutationJournalRecord journal =
                    ((RedisLeaseCoordinator.RecoveryAcquireResult.Acquired) acquired).journal();
            mainExecutor.execute(() -> recoverPlayer(recoveryId, journal, result));
        }
    }

    private void recoverPlayer(
            UUID recoveryId, MutationJournalRecord journal, CompletableFuture<Outcome> result) {
        if (!playerData.isOnline(journal.actorUuid())) {
            asyncExecutor.execute(() -> {
                coordinator.releaseRecovery(recoveryId, true);
                result.complete(Outcome.OFFLINE_PENDING);
            });
            return;
        }

        if (journal.state() == MutationState.CURSOR_STABLE) {
            recoverStableCursor(recoveryId, journal, result);
            return;
        }
        if (journal.state() == MutationState.SETTLEMENT_PREPARED) {
            recoverPreparedSettlement(recoveryId, journal, result);
            return;
        }

        Terminal terminal;
        try {
            Map<SlotRef, SlotValue> observed = playerData.snapshot(journal.actorUuid(), journal.playerPlan());
            RecoveryDecision decision = MutationRecovery.decide(journal.state(), journal.playerPlan(), observed);
            terminal = switch (decision) {
                case ABORT_WITHOUT_PLAYER_CHANGE -> Terminal.ABORT;
                case ACK_WITHOUT_PLAYER_CHANGE -> Terminal.ACK;
                case RESTORE_BEFORE_THEN_ABORT -> applyAndSave(
                        journal, observed, MutationPlan.Phase.BEFORE, Terminal.ABORT);
                case APPLY_AFTER_THEN_ACK -> applyAndSave(
                        journal, observed, MutationPlan.Phase.AFTER, Terminal.ACK);
                case QUARANTINE -> Terminal.QUARANTINE;
            };
        } catch (Exception error) {
            asyncExecutor.execute(() -> {
                coordinator.releaseRecovery(recoveryId, false);
                result.complete(Outcome.PENDING_RETRY);
            });
            return;
        }

        Terminal finalTerminal = terminal;
        asyncExecutor.execute(() -> finish(recoveryId, journal.mutationId(), finalTerminal, result));
    }

    private void recoverStableCursor(UUID recoveryId, MutationJournalRecord journal,
                                     CompletableFuture<Outcome> result) {
        MutationPlan settlement;
        try {
            settlement = playerData.planEscrowSettlement(journal.actorUuid(), journal.playerPlan().escrow());
        } catch (RuntimeException divergence) {
            asyncExecutor.execute(() -> finish(recoveryId, journal.mutationId(),
                    Terminal.QUARANTINE, result));
            return;
        }
        if (settlement == null) {
            asyncExecutor.execute(() -> {
                coordinator.releaseRecovery(recoveryId, true);
                result.complete(Outcome.OFFLINE_PENDING);
            });
            return;
        }
        asyncExecutor.execute(() -> {
            if (!coordinator.prepareSettlementRecovery(recoveryId, journal.mutationId(), settlement)) {
                coordinator.releaseRecovery(recoveryId, false);
                result.complete(Outcome.PENDING_RETRY);
                return;
            }
            mainExecutor.execute(() -> applyRecoverySettlement(recoveryId, journal, settlement, result));
        });
    }

    private void applyRecoverySettlement(UUID recoveryId, MutationJournalRecord journal,
                                         MutationPlan plan, CompletableFuture<Outcome> result) {
        try {
            Map<SlotRef, SlotValue> observed = playerData.snapshot(journal.actorUuid(), plan);
            if (!plan.matches(MutationPlan.Phase.BEFORE, observed)
                    || !playerData.compareAndApply(journal.actorUuid(), plan, observed, MutationPlan.Phase.AFTER)) {
                asyncExecutor.execute(() -> finish(recoveryId, journal.mutationId(),
                        Terminal.QUARANTINE, result));
                return;
            }
            playerData.saveData(journal.actorUuid());
        } catch (Exception error) {
            asyncExecutor.execute(() -> {
                coordinator.releaseRecovery(recoveryId, false);
                result.complete(Outcome.PENDING_RETRY);
            });
            return;
        }
        asyncExecutor.execute(() -> applyAndFinishSettlementRecovery(
                recoveryId, journal.mutationId(), plan, result));
    }

    private void recoverPreparedSettlement(UUID recoveryId, MutationJournalRecord journal,
                                           CompletableFuture<Outcome> result) {
        try {
            Map<SlotRef, SlotValue> observed = playerData.snapshot(journal.actorUuid(), journal.playerPlan());
            if (journal.playerPlan().matches(MutationPlan.Phase.AFTER, observed)) {
                asyncExecutor.execute(() -> applyAndFinishSettlementRecovery(
                        recoveryId, journal.mutationId(), journal.playerPlan(), result));
            } else if (journal.playerPlan().matches(MutationPlan.Phase.BEFORE, observed)) {
                asyncExecutor.execute(() -> {
                    boolean aborted = coordinator.abortSettlementRecovery(
                            recoveryId, journal.mutationId(), journal.playerPlan().settlement().opSequence());
                    coordinator.releaseRecovery(recoveryId, aborted);
                    result.complete(aborted ? Outcome.PENDING_RETRY : Outcome.PROVIDER_UNAVAILABLE);
                });
            } else {
                asyncExecutor.execute(() -> finish(recoveryId, journal.mutationId(),
                        Terminal.QUARANTINE, result));
            }
        } catch (RuntimeException error) {
            asyncExecutor.execute(() -> finish(recoveryId, journal.mutationId(),
                    Terminal.QUARANTINE, result));
        }
    }

    private void finishSettlementRecovery(UUID recoveryId, UUID mutationId, MutationPlan plan,
                                          CompletableFuture<Outcome> result) {
        boolean completed = coordinator.completeSettlementRecovery(recoveryId, mutationId);
        boolean remainder = completed && plan.settlement().nextEscrow() != null;
        coordinator.releaseRecovery(recoveryId, remainder);
        result.complete(completed && !remainder ? Outcome.RECOVERED : Outcome.PENDING_RETRY);
    }

    private void applyAndFinishSettlementRecovery(UUID recoveryId, UUID mutationId,
                                                  MutationPlan plan, CompletableFuture<Outcome> result) {
        if (!coordinator.applySettlementRecovery(
                recoveryId, mutationId, plan.settlement().opSequence())) {
            coordinator.releaseRecovery(recoveryId, false);
            result.complete(Outcome.PENDING_RETRY);
            return;
        }
        finishSettlementRecovery(recoveryId, mutationId, plan, result);
    }

    private Terminal applyAndSave(
            MutationJournalRecord journal,
            Map<SlotRef, SlotValue> observed,
            MutationPlan.Phase target,
            Terminal terminal
    ) throws Exception {
        if (!playerData.compareAndApply(journal.actorUuid(), journal.playerPlan(), observed, target)) {
            return Terminal.QUARANTINE;
        }
        playerData.saveData(journal.actorUuid());
        return terminal;
    }

    private void finish(
            UUID recoveryId, UUID mutationId, Terminal terminal, CompletableFuture<Outcome> result) {
        boolean persisted;
        try {
            persisted = switch (terminal) {
                case ACK -> coordinator.acknowledgeRecovery(recoveryId, mutationId);
                case ABORT -> coordinator.abortRecovery(recoveryId, mutationId);
                case QUARANTINE -> coordinator.quarantineRecovery(recoveryId, mutationId);
            };
        } catch (RuntimeException error) {
            persisted = false;
        }
        coordinator.releaseRecovery(recoveryId, false);
        if (!persisted) {
            result.complete(Outcome.PENDING_RETRY);
        } else if (terminal == Terminal.QUARANTINE) {
            result.complete(Outcome.QUARANTINED);
        } else {
            result.complete(Outcome.RECOVERED);
        }
    }

    private enum Terminal { ACK, ABORT, QUARANTINE }

    public enum Outcome {
        CLEAR,
        RECOVERED,
        BUSY,
        OFFLINE_PENDING,
        PENDING_RETRY,
        QUARANTINED,
        PROVIDER_UNAVAILABLE
    }

    public interface Coordinator {
        RedisLeaseCoordinator.RecoveryAcquireResult acquireRecovery(UUID ownerUuid, UUID recoveryId);
        boolean acknowledgeRecovery(UUID recoveryId, UUID mutationId);
        boolean abortRecovery(UUID recoveryId, UUID mutationId);
        boolean quarantineRecovery(UUID recoveryId, UUID mutationId);
        boolean prepareSettlementRecovery(UUID recoveryId, UUID mutationId, MutationPlan plan);
        boolean abortSettlementRecovery(UUID recoveryId, UUID mutationId, long opSequence);
        boolean completeSettlementRecovery(UUID recoveryId, UUID mutationId);
        boolean applySettlementRecovery(UUID recoveryId, UUID mutationId, long opSequence);
        boolean releaseRecovery(UUID recoveryId, boolean pauseDatabase);
    }

    public interface PlayerDataPort {
        boolean isOnline(UUID actorUuid);
        Map<SlotRef, SlotValue> snapshot(UUID actorUuid, MutationPlan plan);
        boolean compareAndApply(UUID actorUuid, MutationPlan plan, Map<SlotRef, SlotValue> expected,
                                MutationPlan.Phase target);
        void saveData(UUID actorUuid) throws Exception;
        /** Returns null when no deterministic storage-slot destination currently has capacity. */
        MutationPlan planEscrowSettlement(UUID actorUuid, CursorEscrow escrow);
    }
}
