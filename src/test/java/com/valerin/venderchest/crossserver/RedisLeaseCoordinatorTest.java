package com.valerin.venderchest.crossserver;

import com.valerin.venderchest.storage.NetworkSessionStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisLeaseCoordinatorTest {

    @Test
    void pausedAcquireCannotAdvanceFenceOverHealthyDatabaseLease() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID owner = UUID.randomUUID();
        RedisLeaseCoordinator paused = coordinator(redis, mysql, redisClock, "paused");
        RedisLeaseCoordinator active = coordinator(redis, mysql, redisClock, "active");

        mysql.beforeNextClaim = () -> {
            redisClock.advance(30_001);
            assertEquals(1, granted(active.acquire(owner, UUID.randomUUID())));
        };

        assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Busy.class,
                paused.acquire(owner, UUID.randomUUID()));
        assertEquals(1, mysql.fence(owner));
    }

    @Test
    void pauseAfterClaimFailsPostRenewWithoutInvalidatingAnotherSession() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID owner = UUID.randomUUID();
        RedisLeaseCoordinator paused = coordinator(redis, mysql, redisClock, "paused");
        RedisLeaseCoordinator contender = coordinator(redis, mysql, redisClock, "contender");

        mysql.afterNextClaim = () -> {
            redisClock.advance(30_001);
            assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Busy.class,
                    contender.acquire(owner, UUID.randomUUID()));
        };

        assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Unavailable.class,
                paused.acquire(owner, UUID.randomUUID()));
        assertFalse(mysql.hasSession(owner));
        assertEquals(2, granted(contender.acquire(owner, UUID.randomUUID())));
    }

    @Test
    void redisRestartCannotTakeOverUntilDatabaseLeaseExpires() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID owner = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        RedisLeaseCoordinator oldServer = coordinator(redis, mysql, redisClock, "old");
        RedisLeaseCoordinator newServer = coordinator(redis, mysql, redisClock, "new");
        long oldFence = granted(oldServer.acquire(owner, oldSession));

        redis.restart();
        assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Busy.class,
                newServer.acquire(owner, UUID.randomUUID()));
        assertEquals(1, oldServer.renewAndFindFrozen().size());
        assertFalse(oldServer.mayAcceptMutation(oldSession));

        dbClock.advance(45_001);
        long newFence = granted(newServer.acquire(owner, UUID.randomUUID()));
        assertEquals(oldFence + 1, newFence);
        assertFalse(oldServer.release(oldSession));
    }

    @Test
    void redisSuccessWithoutDatabaseHeartbeatFreezesActiveSession() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID session = UUID.randomUUID();
        RedisLeaseCoordinator coordinator = coordinator(redis, mysql, redisClock, "s1");
        assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Granted.class,
                coordinator.acquire(UUID.randomUUID(), session));

        mysql.failRenew = true;
        assertEquals(1, coordinator.renewAndFindFrozen().size());
        assertFalse(coordinator.mayAcceptMutation(session));
    }

    @Test
    void databaseClockAloneControlsTakeoverAndCleanReleaseIsImmediate() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        redisClock.advance(9_000_000);
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID owner = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID();
        RedisLeaseCoordinator first = coordinator(redis, mysql, redisClock, "first");
        RedisLeaseCoordinator second = coordinator(redis, mysql, redisClock, "second");
        long firstFence = granted(first.acquire(owner, firstSession));

        redisClock.advance(30_001);
        assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Busy.class,
                second.acquire(owner, UUID.randomUUID()));
        assertTrue(first.release(firstSession));
        assertEquals(firstFence + 1, granted(second.acquire(owner, UUID.randomUUID())));
    }

    @Test
    void redisPartitionFreezesImmediatelyAndStopsDatabaseRenewal() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID owner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        RedisLeaseCoordinator coordinator = coordinator(redis, mysql, redisClock, "s1");
        coordinator.acquire(owner, session);
        long leaseUntil = mysql.leaseUntil(owner);

        redis.partitioned = true;
        assertEquals(1, coordinator.renewAndFindFrozen().size());
        assertEquals(leaseUntil, mysql.leaseUntil(owner));
        assertFalse(coordinator.mayAcceptMutation(session));
    }

    @Test
    void recoveryWaitsForOldRedisAndDatabaseLeasesThenSerializesWorkers() {
        MutableClock redisClock = new MutableClock();
        MutableClock dbClock = new MutableClock();
        FakeRedis redis = new FakeRedis(redisClock);
        FakeSessions mysql = new FakeSessions(dbClock);
        UUID owner = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        RedisLeaseCoordinator oldServer = coordinator(redis, mysql, redisClock, "old");
        RedisLeaseCoordinator firstWorker = coordinator(redis, mysql, redisClock, "worker-1");
        RedisLeaseCoordinator secondWorker = coordinator(redis, mysql, redisClock, "worker-2");
        long fence = granted(oldServer.acquire(owner, oldSession));
        mysql.recoveryJournal = journal(owner, oldSession, fence);

        assertInstanceOf(RedisLeaseCoordinator.RecoveryAcquireResult.Busy.class,
                firstWorker.acquireRecovery(owner, UUID.randomUUID()));
        assertEquals(0, mysql.recoveryClaims);

        redisClock.advance(30_001);
        dbClock.advance(45_001);
        UUID recoveryId = UUID.randomUUID();
        assertInstanceOf(RedisLeaseCoordinator.RecoveryAcquireResult.Acquired.class,
                firstWorker.acquireRecovery(owner, recoveryId));
        assertInstanceOf(RedisLeaseCoordinator.RecoveryAcquireResult.Busy.class,
                secondWorker.acquireRecovery(owner, UUID.randomUUID()));
        assertEquals(fence, mysql.fence(owner));
    }

    @Test
    void normalCloseRacingRenewReleasesRedisAndDatabaseExactlyOnce() {
        MutableClock clock = new MutableClock();
        FakeRedis redis = new FakeRedis(clock);
        FakeSessions mysql = new FakeSessions(clock);
        UUID owner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        RedisLeaseCoordinator coordinator = coordinator(redis, mysql, clock, "server");
        granted(coordinator.acquire(owner, session));

        assertTrue(coordinator.renewAndFindFrozen().isEmpty());
        assertTrue(coordinator.release(session));
        assertFalse(coordinator.release(session));
        assertTrue(coordinator.renewAndFindFrozen().isEmpty());

        assertEquals(1, redis.releaseCalls);
        assertEquals(1, mysql.releaseCalls);
        assertEquals(2, mysql.renewCalls); // acquire post-renew validation + the explicit heartbeat
    }

    private MutationJournalRecord journal(UUID owner, UUID session, long fence) {
        MutationPlan plan = new MutationPlan(List.of(new SlotMutation(
                new SlotRef(SlotRef.Area.PLAYER, 1), SlotValue.fromText("before"),
                SlotValue.empty(), SlotValue.fromText("after"))));
        return new MutationJournalRecord(UUID.randomUUID(), owner, owner, session, 1, fence,
                1, 0, null, MutationState.PREPARED, plan, "before", "after");
    }

    private RedisLeaseCoordinator coordinator(
            FakeRedis redis, FakeSessions mysql, MutableClock clock, String serverId) {
        return new RedisLeaseCoordinator(redis, mysql, "test:", serverId,
                30_000, 10_000, 45, clock::get, UUID::randomUUID);
    }

    private long granted(RedisLeaseCoordinator.AcquireResult result) {
        return assertInstanceOf(RedisLeaseCoordinator.AcquireResult.Granted.class, result).fence();
    }

    private static final class FakeSessions implements RedisLeaseCoordinator.SessionClaimer {
        private final MutableClock dbClock;
        private final Map<UUID, Row> rows = new HashMap<>();
        private Runnable beforeNextClaim;
        private Runnable afterNextClaim;
        private boolean failRenew;
        private MutationJournalRecord recoveryJournal;
        private UUID recoveryId;
        private int recoveryClaims;
        private int renewCalls;
        private int releaseCalls;

        private FakeSessions(MutableClock dbClock) {
            this.dbClock = dbClock;
        }

        @Override
        public NetworkSessionStore.ClaimResult claim(UUID ownerUuid, UUID sessionId, int leaseSeconds) {
            Runnable before = beforeNextClaim;
            beforeNextClaim = null;
            if (before != null) before.run();
            Row previous = rows.get(ownerUuid);
            if (previous != null && previous.sessionId != null && previous.leaseUntil > dbClock.get()
                    && !previous.sessionId.equals(sessionId)) {
                return new NetworkSessionStore.ClaimResult.Busy();
            }
            long fence = previous == null ? 1 : previous.fence + 1;
            rows.put(ownerUuid, new Row(sessionId, fence, dbClock.get() + leaseSeconds * 1_000L));
            Runnable after = afterNextClaim;
            afterNextClaim = null;
            if (after != null) after.run();
            return new NetworkSessionStore.ClaimResult.Granted(fence);
        }

        @Override
        public boolean renew(UUID ownerUuid, UUID sessionId, long fence, int leaseSeconds) {
            renewCalls++;
            if (failRenew) return false;
            Row row = rows.get(ownerUuid);
            if (row == null || row.leaseUntil <= dbClock.get()
                    || row.fence != fence || !sessionId.equals(row.sessionId)) return false;
            row.leaseUntil = dbClock.get() + leaseSeconds * 1_000L;
            return true;
        }

        @Override
        public boolean release(UUID ownerUuid, UUID sessionId, long fence) {
            releaseCalls++;
            Row row = rows.get(ownerUuid);
            if (row == null || row.fence != fence || !sessionId.equals(row.sessionId)) return false;
            row.sessionId = null;
            row.leaseUntil = 0;
            return true;
        }

        @Override
        public NetworkSessionStore.RecoveryClaim beginRecovery(UUID ownerUuid, UUID recoveryId, int leaseSeconds) {
            recoveryClaims++;
            Row row = rows.get(ownerUuid);
            if (recoveryJournal == null) return new NetworkSessionStore.RecoveryClaim.None();
            if (row != null && row.leaseUntil > dbClock.get()) return new NetworkSessionStore.RecoveryClaim.Busy();
            this.recoveryId = recoveryId;
            row.leaseUntil = dbClock.get() + leaseSeconds * 1_000L;
            return new NetworkSessionStore.RecoveryClaim.Acquired(recoveryJournal);
        }

        @Override public boolean renewRecovery(UUID owner, UUID recovery, long fence, int leaseSeconds) {
            Row row = rows.get(owner);
            if (!recovery.equals(recoveryId) || row == null || row.fence != fence || row.leaseUntil <= dbClock.get()) return false;
            row.leaseUntil = dbClock.get() + leaseSeconds * 1_000L;
            return true;
        }
        @Override public boolean pauseRecovery(UUID owner, UUID recovery, long fence) {
            if (!recovery.equals(recoveryId)) return false;
            recoveryId = null;
            rows.get(owner).leaseUntil = 0;
            return true;
        }
        @Override public boolean acknowledgeRecovery(UUID mutation, UUID recovery) { return recovery.equals(recoveryId); }
        @Override public boolean abortRecovery(UUID mutation, UUID recovery) { return recovery.equals(recoveryId); }
        @Override public boolean quarantineRecovery(UUID mutation, UUID recovery) { return recovery.equals(recoveryId); }
        @Override public boolean prepareSettlementRecovery(UUID mutation, UUID recovery, MutationPlan plan) {
            return recovery.equals(recoveryId);
        }
        @Override public boolean abortSettlementRecovery(UUID mutation, UUID recovery, long opSequence) {
            return recovery.equals(recoveryId);
        }
        @Override public boolean completeSettlementRecovery(UUID mutation, UUID recovery) {
            return recovery.equals(recoveryId);
        }
        @Override public boolean applySettlementRecovery(UUID mutation, UUID recovery, long opSequence) {
            return recovery.equals(recoveryId);
        }

        long fence(UUID owner) { return rows.get(owner).fence; }
        long leaseUntil(UUID owner) { return rows.get(owner).leaseUntil; }
        boolean hasSession(UUID owner) { return rows.get(owner).sessionId != null; }

        private static final class Row {
            private UUID sessionId;
            private final long fence;
            private long leaseUntil;

            private Row(UUID sessionId, long fence, long leaseUntil) {
                this.sessionId = sessionId;
                this.fence = fence;
                this.leaseUntil = leaseUntil;
            }
        }
    }

    private static final class FakeRedis implements RedisLeaseCoordinator.LeaseCommands {
        private final MutableClock clock;
        private final Map<String, Entry> values = new HashMap<>();
        private boolean partitioned;
        private int releaseCalls;

        private FakeRedis(MutableClock clock) { this.clock = clock; }

        @Override
        public boolean acquire(String key, String token, long ttlMillis) {
            available();
            expire(key);
            if (values.containsKey(key)) return false;
            values.put(key, new Entry(token, clock.get() + ttlMillis));
            return true;
        }

        @Override
        public boolean renew(String key, String token, long ttlMillis) {
            available();
            expire(key);
            Entry entry = values.get(key);
            if (entry == null || !entry.token().equals(token)) return false;
            values.put(key, new Entry(token, clock.get() + ttlMillis));
            return true;
        }

        @Override
        public boolean release(String key, String token) {
            releaseCalls++;
            available();
            expire(key);
            Entry entry = values.get(key);
            return entry != null && entry.token().equals(token) && values.remove(key, entry);
        }

        void restart() { values.clear(); }

        private void expire(String key) {
            Entry entry = values.get(key);
            if (entry != null && entry.expiresAt() <= clock.get()) values.remove(key, entry);
        }

        private void available() {
            if (partitioned) throw new IllegalStateException("partition");
        }

        @Override public void close() {}

        private record Entry(String token, long expiresAt) {}
    }

    private static final class MutableClock {
        private long now;
        long get() { return now; }
        void advance(long millis) { now += millis; }
    }
}
