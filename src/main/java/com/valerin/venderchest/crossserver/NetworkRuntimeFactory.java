package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.config.CrossServerSettings;
import com.valerin.venderchest.storage.MysqlStorage;
import com.valerin.venderchest.storage.NetworkSessionStore;

import java.util.concurrent.atomic.AtomicReference;

public final class NetworkRuntimeFactory implements CrossServerLifecycle.RuntimeFactory {

    private final MysqlStorage storage;
    private final AtomicReference<Runtime> active = new AtomicReference<>();

    public NetworkRuntimeFactory(MysqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public CrossServerLifecycle.RuntimeHandle open(CrossServerSettings settings) throws Exception {
        if (!settings.enabled() || !"mysql".equals(settings.databaseType())) {
            throw new IllegalArgumentException("network runtime requires enabled MySQL settings");
        }
        if (!storage.configuredTablePrefix().equals(settings.tablePrefix())) {
            throw new IllegalArgumentException("table prefix differs from the active MySQL storage");
        }
        NetworkSessionStore sessions = new NetworkSessionStore(storage);
        sessions.initSchema();
        RedisLeaseCoordinator coordinator = RedisLeaseCoordinator.connect(
                sessions,
                settings.redis().toRedisUri(),
                settings.redisKeyPrefix(),
                settings.serverId(),
                settings.ttlMillis(),
                settings.safetyMillis(),
                settings.dbLeaseSeconds(),
                settings.redis().timeoutMillis());
        Runtime runtime = new Runtime(sessions, coordinator);
        active.set(runtime);
        return runtime;
    }

    public Runtime activeRuntime() {
        return active.get();
    }

    public final class Runtime implements CrossServerLifecycle.RuntimeHandle {
        private final NetworkSessionStore sessions;
        private final RedisLeaseCoordinator coordinator;

        private Runtime(NetworkSessionStore sessions, RedisLeaseCoordinator coordinator) {
            this.sessions = sessions;
            this.coordinator = coordinator;
        }

        public NetworkSessionStore sessions() { return sessions; }
        public RedisLeaseCoordinator coordinator() { return coordinator; }

        @Override public boolean hasActiveState() throws Exception {
            return coordinator.hasLeases() || sessions.hasActiveState();
        }
        @Override public boolean hasLegacyMutationState() throws Exception {
            return sessions.hasNonTerminalMutation();
        }
        @Override public void tick() { coordinator.renewAndFindFrozen(); }
        @Override public void close() {
            active.compareAndSet(this, null);
            coordinator.close();
        }
    }
}
