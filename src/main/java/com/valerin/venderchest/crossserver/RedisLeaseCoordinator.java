package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.storage.NetworkSessionStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class RedisLeaseCoordinator implements AutoCloseable, CrossServerRecoveryService.Coordinator {

    private final LeaseCommands redis;
    private final SessionClaimer sessions;
    private final String keyPrefix;
    private final String serverId;
    private final long ttlMillis;
    private final long safetyMillis;
    private final int dbLeaseSeconds;
    private final LongSupplier clockMillis;
    private final Supplier<UUID> nonceSupplier;
    private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final Map<UUID, RecoveryLease> recoveryLeases = new ConcurrentHashMap<>();

    public static RedisLeaseCoordinator connect(
            NetworkSessionStore sessions,
            RedisURI redisUri,
            String keyPrefix,
            String serverId,
            long ttlMillis,
            long safetyMillis,
            int dbLeaseSeconds,
            long commandTimeoutMillis
    ) {
        return new RedisLeaseCoordinator(
                new LettuceLeaseCommands(redisUri, commandTimeoutMillis),
                new SessionClaimer() {
                    @Override
                    public NetworkSessionStore.ClaimResult claim(UUID ownerUuid, UUID sessionId, int leaseSeconds) {
                        return sessions.claimSession(ownerUuid, sessionId, leaseSeconds);
                    }

                    @Override
                    public boolean renew(UUID ownerUuid, UUID sessionId, long fence, int leaseSeconds) {
                        return sessions.renewSession(ownerUuid, sessionId, fence, leaseSeconds);
                    }

                    @Override
                    public boolean release(UUID ownerUuid, UUID sessionId, long fence) {
                        return sessions.releaseSession(ownerUuid, sessionId, fence);
                    }

                    @Override
                    public NetworkSessionStore.RecoveryClaim beginRecovery(
                            UUID ownerUuid, UUID recoveryId, int leaseSeconds) {
                        return sessions.beginRecovery(ownerUuid, recoveryId, leaseSeconds);
                    }

                    @Override
                    public boolean renewRecovery(UUID ownerUuid, UUID recoveryId, long fence, int leaseSeconds) {
                        return sessions.renewRecovery(ownerUuid, recoveryId, fence, leaseSeconds);
                    }

                    @Override
                    public boolean pauseRecovery(UUID ownerUuid, UUID recoveryId, long fence) {
                        return sessions.pauseRecovery(ownerUuid, recoveryId, fence);
                    }

                    @Override
                    public boolean acknowledgeRecovery(UUID mutationId, UUID recoveryId) {
                        return sessions.acknowledgeRecovery(mutationId, recoveryId);
                    }

                    @Override
                    public boolean abortRecovery(UUID mutationId, UUID recoveryId) {
                        return sessions.abortRecovery(mutationId, recoveryId);
                    }

                    @Override
                    public boolean quarantineRecovery(UUID mutationId, UUID recoveryId) {
                        return sessions.quarantineRecovery(mutationId, recoveryId);
                    }

                    @Override
                    public boolean prepareSettlementRecovery(UUID mutationId, UUID recoveryId,
                                                             MutationPlan plan) {
                        return sessions.prepareSettlementRecovery(mutationId, recoveryId, plan);
                    }

                    @Override
                    public boolean abortSettlementRecovery(UUID mutationId, UUID recoveryId,
                                                           long opSequence) {
                        return sessions.abortSettlementRecovery(mutationId, recoveryId, opSequence);
                    }

                    @Override
                    public boolean completeSettlementRecovery(UUID mutationId, UUID recoveryId) {
                        return sessions.completeSettlementRecovery(mutationId, recoveryId);
                    }

                    @Override
                    public boolean applySettlementRecovery(UUID mutationId, UUID recoveryId,
                                                           long opSequence) {
                        return sessions.applySettlementRecovery(mutationId, recoveryId, opSequence);
                    }
                },
                keyPrefix,
                serverId,
                ttlMillis,
                safetyMillis,
                dbLeaseSeconds,
                System::currentTimeMillis,
                UUID::randomUUID
        );
    }

    RedisLeaseCoordinator(
            LeaseCommands redis,
            SessionClaimer sessions,
            String keyPrefix,
            String serverId,
            long ttlMillis,
            long safetyMillis,
            int dbLeaseSeconds,
            LongSupplier clockMillis,
            Supplier<UUID> nonceSupplier
    ) {
        if (ttlMillis <= 0 || safetyMillis <= 0 || safetyMillis >= ttlMillis) {
            throw new IllegalArgumentException("lease safety must be between zero and TTL");
        }
        if (dbLeaseSeconds < 1) throw new IllegalArgumentException("DB lease must be positive");
        this.redis = redis;
        this.sessions = sessions;
        this.keyPrefix = keyPrefix;
        this.serverId = serverId;
        this.ttlMillis = ttlMillis;
        this.safetyMillis = safetyMillis;
        this.dbLeaseSeconds = dbLeaseSeconds;
        this.clockMillis = clockMillis;
        this.nonceSupplier = nonceSupplier;
    }

    /** Blocking network/DB operation. Call only from an async worker. */
    public AcquireResult acquire(UUID ownerUuid, UUID sessionId) {
        String key = key(ownerUuid);
        String token = serverId + ":" + sessionId + ":" + nonceSupplier.get();
        try {
            if (!redis.acquire(key, token, ttlMillis)) return new AcquireResult.Busy();
        } catch (RuntimeException e) {
            return new AcquireResult.Unavailable("redis unavailable");
        }

        NetworkSessionStore.ClaimResult claim = sessions.claim(ownerUuid, sessionId, dbLeaseSeconds);
        if (claim instanceof NetworkSessionStore.ClaimResult.Granted granted) {
            boolean redisConfirmed;
            try {
                redisConfirmed = redis.renew(key, token, ttlMillis);
            } catch (RuntimeException e) {
                redisConfirmed = false;
            }
            boolean dbConfirmed = redisConfirmed
                    && sessions.renew(ownerUuid, sessionId, granted.fence(), dbLeaseSeconds);
            if (dbConfirmed) {
                long confirmedAt = clockMillis.getAsLong();
                leases.put(sessionId, new Lease(ownerUuid, sessionId, key, token, granted.fence(), confirmedAt));
                return new AcquireResult.Granted(granted.fence());
            }
            sessions.release(ownerUuid, sessionId, granted.fence());
            releaseRedis(key, token);
            return new AcquireResult.Unavailable("lease changed before post-claim validation");
        }

        releaseRedis(key, token);
        if (claim instanceof NetworkSessionStore.ClaimResult.Busy) {
            return new AcquireResult.Busy();
        }
        if (claim instanceof NetworkSessionStore.ClaimResult.RecoveryRequired recovery) {
            return new AcquireResult.RecoveryRequired(recovery.mutationId());
        }
        return new AcquireResult.Unavailable("database unavailable");
    }

    /**
     * Renews every tracked lease. Any timeout, partition, token mismatch or missed safety cutoff
     * freezes new interactions immediately; callers decide outstanding durable mutations in MySQL.
     */
    public Collection<UUID> renewAndFindFrozen() {
        long now = clockMillis.getAsLong();
        List<UUID> frozen = new ArrayList<>();
        for (Lease lease : List.copyOf(leases.values())) {
            boolean confirmed = false;
            try {
                confirmed = redis.renew(lease.key(), lease.token(), ttlMillis);
            } catch (RuntimeException ignored) {
                // No Redis response is never treated as an assumed-valid lease.
            }
            if (confirmed) {
                confirmed = sessions.renew(
                        lease.ownerUuid(), lease.sessionId(), lease.fence(), dbLeaseSeconds);
            }
            if (confirmed) {
                lease.confirmedAt = now;
            } else {
                lease.frozen = true;
            }
            if (now - lease.confirmedAt >= ttlMillis - safetyMillis) lease.frozen = true;
            if (lease.frozen) frozen.add(lease.sessionId());
        }
        for (RecoveryLease lease : List.copyOf(recoveryLeases.values())) {
            boolean confirmed = false;
            try {
                confirmed = redis.renew(lease.key(), lease.token(), ttlMillis);
            } catch (RuntimeException ignored) {
            }
            if (confirmed) {
                confirmed = sessions.renewRecovery(
                        lease.ownerUuid(), lease.recoveryId(), lease.fence(), dbLeaseSeconds);
            }
            if (!confirmed) lease.frozen = true;
            if (lease.frozen) frozen.add(lease.recoveryId());
        }
        return List.copyOf(frozen);
    }

    /** Blocking Redis/DB recovery acquisition. Call only from the lifecycle executor. */
    @Override
    public RecoveryAcquireResult acquireRecovery(UUID ownerUuid, UUID recoveryId) {
        String key = key(ownerUuid);
        String token = serverId + ":recovery:" + recoveryId + ":" + nonceSupplier.get();
        try {
            if (!redis.acquire(key, token, ttlMillis)) return new RecoveryAcquireResult.Busy();
        } catch (RuntimeException e) {
            return new RecoveryAcquireResult.Unavailable("redis unavailable");
        }

        NetworkSessionStore.RecoveryClaim claim = sessions.beginRecovery(ownerUuid, recoveryId, dbLeaseSeconds);
        if (claim instanceof NetworkSessionStore.RecoveryClaim.Acquired acquired) {
            long fence = acquired.journal().fencingToken();
            boolean redisConfirmed;
            try {
                redisConfirmed = redis.renew(key, token, ttlMillis);
            } catch (RuntimeException e) {
                redisConfirmed = false;
            }
            boolean dbConfirmed = redisConfirmed
                    && sessions.renewRecovery(ownerUuid, recoveryId, fence, dbLeaseSeconds);
            if (dbConfirmed) {
                recoveryLeases.put(recoveryId,
                        new RecoveryLease(ownerUuid, recoveryId, key, token, fence));
                return new RecoveryAcquireResult.Acquired(acquired.journal());
            }
            sessions.pauseRecovery(ownerUuid, recoveryId, fence);
            releaseRedis(key, token);
            return new RecoveryAcquireResult.Unavailable("recovery lease changed before validation");
        }

        releaseRedis(key, token);
        if (claim instanceof NetworkSessionStore.RecoveryClaim.None) return new RecoveryAcquireResult.None();
        if (claim instanceof NetworkSessionStore.RecoveryClaim.Busy) return new RecoveryAcquireResult.Busy();
        if (claim instanceof NetworkSessionStore.RecoveryClaim.Quarantined quarantined) {
            return new RecoveryAcquireResult.Quarantined(quarantined.mutationId());
        }
        return new RecoveryAcquireResult.Unavailable("database unavailable");
    }

    @Override
    public boolean acknowledgeRecovery(UUID recoveryId, UUID mutationId) {
        return sessions.acknowledgeRecovery(mutationId, recoveryId);
    }

    @Override
    public boolean abortRecovery(UUID recoveryId, UUID mutationId) {
        return sessions.abortRecovery(mutationId, recoveryId);
    }

    @Override
    public boolean quarantineRecovery(UUID recoveryId, UUID mutationId) {
        return sessions.quarantineRecovery(mutationId, recoveryId);
    }

    @Override
    public boolean prepareSettlementRecovery(UUID recoveryId, UUID mutationId, MutationPlan plan) {
        return sessions.prepareSettlementRecovery(mutationId, recoveryId, plan);
    }

    @Override
    public boolean abortSettlementRecovery(UUID recoveryId, UUID mutationId, long opSequence) {
        return sessions.abortSettlementRecovery(mutationId, recoveryId, opSequence);
    }

    @Override
    public boolean completeSettlementRecovery(UUID recoveryId, UUID mutationId) {
        return sessions.completeSettlementRecovery(mutationId, recoveryId);
    }

    @Override
    public boolean applySettlementRecovery(UUID recoveryId, UUID mutationId, long opSequence) {
        return sessions.applySettlementRecovery(mutationId, recoveryId, opSequence);
    }

    @Override
    public boolean releaseRecovery(UUID recoveryId, boolean pauseDatabase) {
        RecoveryLease lease = recoveryLeases.remove(recoveryId);
        if (lease == null) return false;
        releaseRedis(lease.key(), lease.token());
        return !pauseDatabase || sessions.pauseRecovery(
                lease.ownerUuid(), recoveryId, lease.fence());
    }

    public boolean hasLeases() {
        return !leases.isEmpty() || !recoveryLeases.isEmpty();
    }

    /** Local admission only; database fence+journal remain the final commit authority. */
    public boolean mayAcceptMutation(UUID sessionId) {
        Lease lease = leases.get(sessionId);
        if (lease == null || lease.frozen) return false;
        return clockMillis.getAsLong() - lease.confirmedAt < ttlMillis - safetyMillis;
    }

    public long fencingToken(UUID sessionId) {
        Lease lease = leases.get(sessionId);
        return lease == null ? -1 : lease.fence();
    }

    /** Blocking Redis/DB cleanup. Call off-main, or during plugin disable. */
    public boolean release(UUID sessionId) {
        Lease lease = leases.remove(sessionId);
        if (lease == null) return false;
        try {
            redis.release(lease.key(), lease.token());
        } catch (RuntimeException ignored) {
        }
        boolean dbReleased = sessions.release(lease.ownerUuid(), lease.sessionId(), lease.fence());
        return dbReleased;
    }

    @Override
    public void close() {
        for (UUID sessionId : List.copyOf(leases.keySet())) release(sessionId);
        for (UUID recoveryId : List.copyOf(recoveryLeases.keySet())) releaseRecovery(recoveryId, true);
        redis.close();
    }

    private String key(UUID ownerUuid) {
        return keyPrefix + "lease:owner:" + ownerUuid;
    }

    private void releaseRedis(String key, String token) {
        try {
            redis.release(key, token);
        } catch (RuntimeException ignored) {
            // TTL plus the compare-token script remains the release backstop.
        }
    }

    private static final class Lease {
        private final UUID ownerUuid;
        private final UUID sessionId;
        private final String key;
        private final String token;
        private final long fence;
        private volatile long confirmedAt;
        private volatile boolean frozen;

        private Lease(UUID ownerUuid, UUID sessionId, String key, String token, long fence, long confirmedAt) {
            this.ownerUuid = ownerUuid;
            this.sessionId = sessionId;
            this.key = key;
            this.token = token;
            this.fence = fence;
            this.confirmedAt = confirmedAt;
        }

        UUID ownerUuid() { return ownerUuid; }
        UUID sessionId() { return sessionId; }
        String key() { return key; }
        String token() { return token; }
        long fence() { return fence; }
    }

    private static final class RecoveryLease {
        private final UUID ownerUuid;
        private final UUID recoveryId;
        private final String key;
        private final String token;
        private final long fence;
        private volatile boolean frozen;

        private RecoveryLease(UUID ownerUuid, UUID recoveryId, String key, String token, long fence) {
            this.ownerUuid = ownerUuid;
            this.recoveryId = recoveryId;
            this.key = key;
            this.token = token;
            this.fence = fence;
        }

        UUID ownerUuid() { return ownerUuid; }
        UUID recoveryId() { return recoveryId; }
        String key() { return key; }
        String token() { return token; }
        long fence() { return fence; }
    }

    interface LeaseCommands extends AutoCloseable {
        boolean acquire(String key, String token, long ttlMillis);
        boolean renew(String key, String token, long ttlMillis);
        boolean release(String key, String token);
        @Override void close();
    }

    interface SessionClaimer {
        NetworkSessionStore.ClaimResult claim(UUID ownerUuid, UUID sessionId, int leaseSeconds);
        boolean renew(UUID ownerUuid, UUID sessionId, long fence, int leaseSeconds);
        boolean release(UUID ownerUuid, UUID sessionId, long fence);
        NetworkSessionStore.RecoveryClaim beginRecovery(UUID ownerUuid, UUID recoveryId, int leaseSeconds);
        boolean renewRecovery(UUID ownerUuid, UUID recoveryId, long fence, int leaseSeconds);
        boolean pauseRecovery(UUID ownerUuid, UUID recoveryId, long fence);
        boolean acknowledgeRecovery(UUID mutationId, UUID recoveryId);
        boolean abortRecovery(UUID mutationId, UUID recoveryId);
        boolean quarantineRecovery(UUID mutationId, UUID recoveryId);
        boolean prepareSettlementRecovery(UUID mutationId, UUID recoveryId, MutationPlan plan);
        boolean abortSettlementRecovery(UUID mutationId, UUID recoveryId, long opSequence);
        boolean completeSettlementRecovery(UUID mutationId, UUID recoveryId);
        boolean applySettlementRecovery(UUID mutationId, UUID recoveryId, long opSequence);
    }

    public sealed interface AcquireResult {
        record Granted(long fence) implements AcquireResult {}
        record Busy() implements AcquireResult {}
        record RecoveryRequired(String mutationId) implements AcquireResult {}
        record Unavailable(String reason) implements AcquireResult {}
    }

    public sealed interface RecoveryAcquireResult {
        record Acquired(MutationJournalRecord journal) implements RecoveryAcquireResult {}
        record None() implements RecoveryAcquireResult {}
        record Busy() implements RecoveryAcquireResult {}
        record Quarantined(String mutationId) implements RecoveryAcquireResult {}
        record Unavailable(String reason) implements RecoveryAcquireResult {}
    }

    private static final class LettuceLeaseCommands implements LeaseCommands {

        private static final String RENEW = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('pexpire', KEYS[1], ARGV[2])
                end
                return 0
                """;
        private static final String RELEASE = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                end
                return 0
                """;

        private final RedisClient client;
        private final StatefulRedisConnection<String, String> connection;
        private final RedisCommands<String, String> commands;

        private LettuceLeaseCommands(RedisURI redisUri, long timeoutMillis) {
            redisUri.setTimeout(Duration.ofMillis(timeoutMillis));
            client = RedisClient.create(redisUri);
            client.setOptions(ClientOptions.builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .requestQueueSize(128)
                    .build());
            connection = client.connect();
            commands = connection.sync();
            commands.ping();
        }

        @Override
        public boolean acquire(String key, String token, long ttlMillis) {
            return "OK".equals(commands.set(key, token, SetArgs.Builder.nx().px(ttlMillis)));
        }

        @Override
        public boolean renew(String key, String token, long ttlMillis) {
            Long result = commands.eval(RENEW, ScriptOutputType.INTEGER,
                    new String[]{key}, token, Long.toString(ttlMillis));
            return result != null && result == 1;
        }

        @Override
        public boolean release(String key, String token) {
            Long result = commands.eval(RELEASE, ScriptOutputType.INTEGER, new String[]{key}, token);
            return result != null && result == 1;
        }

        @Override
        public void close() {
            connection.close();
            client.shutdown();
        }
    }
}
