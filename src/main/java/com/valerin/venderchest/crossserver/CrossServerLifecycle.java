package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.config.CrossServerSettings;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CrossServerLifecycle implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final RuntimeFactory runtimeFactory;
    private final long closeTimeoutMillis;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final ThreadLocal<Boolean> lifecycleThread = ThreadLocal.withInitial(() -> false);
    private volatile CrossServerSettings activeSettings;
    private volatile RuntimeHandle runtime;
    private ScheduledFuture<?> renewalTask;

    public CrossServerLifecycle(RuntimeFactory runtimeFactory) {
        this(runtimeFactory, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vEnderchest-cross-server");
            thread.setDaemon(true);
            return thread;
        }), 5_000);
    }

    CrossServerLifecycle(RuntimeFactory runtimeFactory, ScheduledExecutorService executor) {
        this(runtimeFactory, executor, 5_000);
    }

    CrossServerLifecycle(RuntimeFactory runtimeFactory, ScheduledExecutorService executor, long closeTimeoutMillis) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.closeTimeoutMillis = closeTimeoutMillis;
    }

    public CompletableFuture<Result> start(CrossServerSettings.Validation candidate) {
        return submit(() -> {
            if (state.get() != State.NEW) return Result.rejected("cross-server lifecycle already started");
            if (!candidate.isValid()) return Result.rejected(String.join("; ", candidate.errors()));
            CrossServerSettings settings = candidate.settings();
            if (!settings.enabled()) {
                activeSettings = settings;
                state.set(State.SINGLE_SERVER);
                return Result.accepted(State.SINGLE_SERVER);
            }
            state.set(State.STARTING);
            try {
                RuntimeHandle opened = runtimeFactory.open(settings);
                if (opened.hasLegacyMutationState()) {
                    opened.close();
                    state.set(State.FAILED);
                    return Result.rejected("cross-server blocked: non-terminal legacy mutation journal exists");
                }
                runtime = opened;
                activeSettings = settings;
                state.set(State.ACTIVE);
                scheduleRenewal(settings);
                return Result.accepted(State.ACTIVE);
            } catch (Exception e) {
                state.set(State.FAILED);
                return Result.rejected(safeReason(e));
            }
        });
    }

    public CompletableFuture<Result> reload(CrossServerSettings.Validation candidate) {
        return submit(() -> {
            if (!candidate.isValid()) return Result.rejected(String.join("; ", candidate.errors()));
            if (state.get() == State.NEW || state.get() == State.CLOSED) {
                return Result.rejected("cross-server lifecycle is not reloadable in state " + state.get());
            }
            CrossServerSettings next = candidate.settings();
            if (next.equals(activeSettings)) return Result.accepted(state.get());
            if (activeSettings != null
                    && (!next.databaseType().equals(activeSettings.databaseType())
                    || !next.tablePrefix().equals(activeSettings.tablePrefix()))) {
                return Result.rejected("database.type or database.table-prefix changes require a restart");
            }

            RuntimeHandle current = runtime;
            if (current != null) {
                final boolean active;
                try {
                    active = current.hasActiveState();
                } catch (Exception e) {
                    return Result.rejected("could not verify active cross-server state");
                }
                if (active) {
                    return Result.rejected("leases, sessions, or a non-terminal journal still exist");
                }
            }

            RuntimeHandle replacement = null;
            if (next.enabled()) {
                try {
                    replacement = runtimeFactory.open(next);
                } catch (Exception e) {
                    return Result.rejected(safeReason(e));
                }
            }

            cancelRenewal();
            if (current != null) current.close();
            runtime = replacement;
            activeSettings = next;
            state.set(next.enabled() ? State.ACTIVE : State.SINGLE_SERVER);
            if (replacement != null) scheduleRenewal(next);
            return Result.accepted(state.get());
        });
    }

    public State state() { return state.get(); }
    public CrossServerSettings activeSettings() { return activeSettings; }

    private void scheduleRenewal(CrossServerSettings settings) {
        renewalTask = executor.scheduleWithFixedDelay(() -> {
            lifecycleThread.set(true);
            try {
                RuntimeHandle current = runtime;
                if (current == null || state.get() != State.ACTIVE) return;
                try {
                    current.tick();
                } catch (RuntimeException ignored) {
                    // A runtime tick must freeze its own leases; lifecycle stays available for recovery/status.
                }
            } finally {
                lifecycleThread.remove();
            }
        }, settings.renewMillis(), settings.renewMillis(), TimeUnit.MILLISECONDS);
    }

    private void cancelRenewal() {
        if (renewalTask != null) renewalTask.cancel(false);
        renewalTask = null;
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                lifecycleThread.set(true);
                try {
                    future.complete(operation.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    lifecycleThread.remove();
                }
            });
        } catch (RuntimeException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    @Override
    public void close() {
        if (state.get() == State.CLOSED) return;
        if (lifecycleThread.get()) {
            closeNow();
            executor.shutdownNow();
            return;
        }
        CompletableFuture<Void> closed = submit(() -> {
            closeNow();
            return null;
        });
        try {
            closed.get(closeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            state.set(State.CLOSED);
        } finally {
            executor.shutdownNow();
        }
    }

    private void closeNow() {
        cancelRenewal();
        RuntimeHandle current = runtime;
        runtime = null;
        if (current != null) current.close();
        state.set(State.CLOSED);
    }

    private static String safeReason(Exception error) {
        return "cross-server runtime startup failed (" + error.getClass().getSimpleName() + ")";
    }

    public enum State { NEW, STARTING, SINGLE_SERVER, ACTIVE, FAILED, CLOSED }

    public record Result(boolean accepted, State state, List<String> reasons) {
        public Result { reasons = List.copyOf(reasons); }
        static Result accepted(State state) { return new Result(true, state, List.of()); }
        static Result rejected(String reason) { return new Result(false, null, List.of(reason)); }
    }

    public interface RuntimeFactory {
        RuntimeHandle open(CrossServerSettings settings) throws Exception;
    }

    public interface RuntimeHandle extends AutoCloseable {
        boolean hasActiveState() throws Exception;
        default boolean hasLegacyMutationState() throws Exception { return false; }
        void tick();
        @Override void close();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
