package com.valerin.venderchest.storage;

import com.valerin.venderchest.crossserver.MutationJournalRecord;
import com.valerin.venderchest.crossserver.CursorEscrow;
import com.valerin.venderchest.crossserver.CursorSettlement;
import com.valerin.venderchest.crossserver.MutationPlan;
import com.valerin.venderchest.crossserver.MutationState;
import com.valerin.venderchest.crossserver.SlotMutation;
import com.valerin.venderchest.crossserver.SlotRef;
import com.valerin.venderchest.crossserver.SlotValue;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSessionStoreTest {

    private TestStorage storage;
    private NetworkSessionStore network;

    @BeforeEach
    void setUp() throws Exception {
        storage = new TestStorage();
        storage.init();
        network = new NetworkSessionStore(storage);
        network.initSchema();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void onlyOneMutationCanBeInFlightAndReplayIsIdempotent() {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        MutationJournalRecord first = mutation(owner, actor, session, 1, fence, 0);
        MutationJournalRecord second = mutation(owner, actor, session, 2, fence, 0);

        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(first));
        assertEquals(NetworkSessionStore.PrepareResult.REPLAY, network.prepare(first));
        assertEquals(NetworkSessionStore.PrepareResult.BUSY, network.prepare(second));

        Storage.SaveResult.Success committed = assertInstanceOf(
                Storage.SaveResult.Success.class, network.commitPrepared(first.mutationId()));
        assertEquals(1, committed.newRevision());
        assertInstanceOf(Storage.SaveResult.Success.class, network.commitPrepared(first.mutationId()));
        assertTrue(network.acknowledge(first.mutationId()));
        assertTrue(network.acknowledge(first.mutationId()));

        MutationJournalRecord next = mutation(owner, actor, session, 2, fence, 1);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(next));
    }

    @Test
    void newerFenceRejectsOldWriterAndDelayedReleaseCannotClearNewSession() {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        UUID newSession = UUID.randomUUID();
        long oldFence = granted(network.claimSession(owner, oldSession, 45));
        assertTrue(network.releaseSession(owner, oldSession, oldFence));
        long newFence = granted(network.claimSession(owner, newSession, 45));

        assertEquals(oldFence + 1, newFence);
        assertEquals(NetworkSessionStore.PrepareResult.STALE_FENCE,
                network.prepare(mutation(owner, actor, oldSession, 1, oldFence, 0)));
        assertTrue(!network.releaseSession(owner, oldSession, oldFence));
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED,
                network.prepare(mutation(owner, actor, newSession, 1, newFence, 0)));
    }

    @Test
    void activeJournalBlocksAnotherServerAndSurvivesForRecovery() {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        MutationJournalRecord mutation = mutation(owner, actor, session, 1, fence, 0);

        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(mutation));
        NetworkSessionStore.ClaimResult.RecoveryRequired blocked = assertInstanceOf(
                NetworkSessionStore.ClaimResult.RecoveryRequired.class,
                network.claimSession(owner, UUID.randomUUID(), 45));
        assertEquals(mutation.mutationId().toString(), blocked.mutationId());
        assertEquals(mutation, network.activeMutation(owner).orElseThrow());
    }

    @Test
    void healthyDbLeaseBlocksTakeoverAndCrashTakeoverUsesDatabaseTime() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID();
        long firstFence = granted(network.claimSession(owner, firstSession, 45));

        assertInstanceOf(NetworkSessionStore.ClaimResult.Busy.class,
                network.claimSession(owner, UUID.randomUUID(), 45));
        try (var c = storage.dataSource.getConnection(); var ps = c.prepareStatement(
                "UPDATE " + storage.table("player_fence")
                        + " SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            ps.setString(1, owner.toString());
            assertEquals(1, ps.executeUpdate());
        }

        long takeoverFence = granted(network.claimSession(owner, UUID.randomUUID(), 45));
        assertEquals(firstFence + 1, takeoverFence);
    }

    @Test
    void configuredPrefixOwnsBothVaultAndNetworkTables() throws Exception {
        TestStorage custom = new TestStorage("network_");
        try {
            custom.init();
            new NetworkSessionStore(custom).initSchema();
            try (var c = custom.dataSource.getConnection(); var stmt = c.createStatement()) {
                assertTrue(stmt.executeQuery("SELECT COUNT(*) FROM network_pages").next());
                assertTrue(stmt.executeQuery("SELECT COUNT(*) FROM network_player_fence").next());
                assertTrue(stmt.executeQuery("SELECT COUNT(*) FROM network_mutation_journal").next());
            }
        } finally {
            custom.close();
        }
    }

    @Test
    void lateTerminalCallbacksCannotClearAnotherMutation() {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        MutationJournalRecord first = mutation(owner, actor, session, 1, fence, 0);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(first));
        assertInstanceOf(Storage.SaveResult.Success.class, network.commitPrepared(first.mutationId()));
        assertTrue(network.acknowledge(first.mutationId()));

        MutationJournalRecord second = mutation(owner, actor, session, 2, fence, 1);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(second));
        assertTrue(!network.abortPrepared(first.mutationId()));
        network.quarantine(first.mutationId());
        assertEquals(second, network.activeMutation(owner).orElseThrow());
    }

    @Test
    void recoveryKeepsFenceAndSerializesWorkersBeforeTerminalAbort() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        MutationJournalRecord mutation = mutation(owner, actor, session, 1, fence, 0);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(mutation));
        expireLease(owner);

        UUID worker = UUID.randomUUID();
        var acquired = assertInstanceOf(NetworkSessionStore.RecoveryClaim.Acquired.class,
                network.beginRecovery(owner, worker, 45));
        assertEquals(mutation, acquired.journal());
        assertInstanceOf(NetworkSessionStore.RecoveryClaim.Busy.class,
                network.beginRecovery(owner, UUID.randomUUID(), 45));
        assertTrue(!network.abortPrepared(mutation.mutationId()));

        assertTrue(network.abortRecovery(mutation.mutationId(), worker));
        long nextFence = granted(network.claimSession(owner, UUID.randomUUID(), 45));
        assertEquals(fence + 1, nextFence);
    }

    @Test
    void recoveryQuarantineRemainsBlockedAndCannotBeForceClaimed() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        MutationJournalRecord mutation = mutation(owner, actor, session, 1, fence, 0);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(mutation));
        expireLease(owner);
        UUID worker = UUID.randomUUID();
        assertInstanceOf(NetworkSessionStore.RecoveryClaim.Acquired.class,
                network.beginRecovery(owner, worker, 45));

        assertTrue(network.quarantineRecovery(mutation.mutationId(), worker));
        assertInstanceOf(NetworkSessionStore.RecoveryClaim.Quarantined.class,
                network.beginRecovery(owner, UUID.randomUUID(), 45));
        assertInstanceOf(NetworkSessionStore.ClaimResult.RecoveryRequired.class,
                network.claimSession(owner, UUID.randomUUID(), 45));
    }

    @Test
    void cursorSettlementUsesSameGuardAndRejectsOldOperationSequence() {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        UUID mutationId = UUID.randomUUID();
        SlotValue item = SlotValue.fromText("canonical-item");
        CursorEscrow escrow = new CursorEscrow(mutationId, 1, item,
                SlotValue.fromText("tagged-projection"), List.of());
        String vaultBefore = storage.emptyVault();
        String vault = "committed-vault-payload";
        MutationJournalRecord initial = new MutationJournalRecord(mutationId, owner, actor, session,
                1, fence, 1, 0, null, MutationState.PREPARED,
                MutationPlan.cursorStable(escrow), vaultBefore, vault);

        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(initial));
        assertEquals(1, assertInstanceOf(Storage.SaveResult.Success.class,
                network.commitPrepared(mutationId)).newRevision());
        MutationJournalRecord stable = network.activeMutation(owner).orElseThrow();
        assertEquals(1, stable.baseRevision());
        assertEquals(vault, stable.vaultBefore());
        assertEquals(vault, stable.vaultAfter());

        SlotMutation destination = new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, 2),
                SlotValue.empty(), SlotValue.empty(), item);
        CursorSettlement transition = new CursorSettlement(CursorSettlement.Kind.CURSOR_TO_PLAYER,
                CursorSettlement.Stage.PLANNED, 2, escrow.projection(), SlotValue.empty(), null);
        MutationPlan settlement = MutationPlan.settlement(List.of(destination), escrow, transition);
        assertTrue(network.prepareSettlement(mutationId, 1, settlement, 1, 1, vault, vault));
        assertTrue(!network.prepareSettlement(mutationId, 1, settlement, 1, 1, vault, vault));

        NetworkSessionStore.SettlementResult applied = network.applySettlement(mutationId, 2);
        assertTrue(applied.success());
        assertEquals(CursorSettlement.Stage.VAULT_APPLIED, applied.plan().settlement().stage());
        assertTrue(network.completeSettlement(mutationId, 2));
        assertTrue(!network.completeSettlement(mutationId, 1));

        assertEquals(NetworkSessionStore.PrepareResult.PREPARED,
                network.prepare(mutation(owner, actor, session, 2, fence, 0)));
    }

    @Test
    void recoveryReplayAfterVaultAppliedNeverRepeatsCas() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        UUID mutationId = UUID.randomUUID();
        SlotValue item = SlotValue.fromText("item");
        CursorEscrow escrow = new CursorEscrow(mutationId, 1, item,
                SlotValue.fromText("projection"), List.of());
        String empty = storage.emptyVault();
        String first = "vault-revision-one";
        MutationJournalRecord initial = new MutationJournalRecord(mutationId, owner, actor, session,
                1, fence, 1, 0, null, MutationState.PREPARED,
                MutationPlan.cursorStable(escrow), empty, first);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(initial));
        assertEquals(1, assertInstanceOf(Storage.SaveResult.Success.class,
                network.commitPrepared(mutationId)).newRevision());

        CursorSettlement transition = new CursorSettlement(CursorSettlement.Kind.CURSOR_TO_VAULT,
                CursorSettlement.Stage.PLANNED, 2, escrow.projection(), SlotValue.empty(), null);
        MutationPlan settlement = MutationPlan.settlement(List.of(), escrow, transition);
        String second = "vault-revision-two";
        assertTrue(network.prepareSettlement(mutationId, 1, settlement, 1, 1, first, second));
        expireLease(owner);
        UUID recovery = UUID.randomUUID();
        assertInstanceOf(NetworkSessionStore.RecoveryClaim.Acquired.class,
                network.beginRecovery(owner, recovery, 45));

        assertTrue(!network.applySettlement(mutationId, 2).success());
        assertTrue(network.applySettlementRecovery(mutationId, recovery, 2));
        assertEquals(2, pageRevision(owner));
        assertTrue(network.applySettlementRecovery(mutationId, recovery, 2));
        assertEquals(2, pageRevision(owner));
        assertTrue(!network.applySettlementRecovery(mutationId, recovery, 1));
        assertTrue(network.completeSettlementRecovery(mutationId, recovery));
    }

    @Test
    void cursorPayloadLimitIncludesCanonicalAndTaggedProjection() {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        UUID mutationId = UUID.randomUUID();
        byte[] oversized = new byte[4_500_000];
        SlotValue canonical = SlotValue.fromBytes(oversized);
        CursorEscrow escrow = new CursorEscrow(mutationId, 1, canonical, canonical, List.of());
        String vault = storage.emptyVault();
        MutationJournalRecord record = new MutationJournalRecord(mutationId, owner, actor, session,
                1, fence, 1, 0, null, MutationState.PREPARED,
                MutationPlan.cursorStable(escrow), vault, vault);

        assertEquals(NetworkSessionStore.PrepareResult.PAYLOAD_TOO_LARGE, network.prepare(record));
    }

    @Test
    void settlementCasConflictNeverOverwritesNewerVault() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long fence = granted(network.claimSession(owner, session, 45));
        UUID mutationId = UUID.randomUUID();
        SlotValue item = SlotValue.fromText("item");
        CursorEscrow escrow = new CursorEscrow(mutationId, 1, item,
                SlotValue.fromText("projection"), List.of());
        String empty = storage.emptyVault();
        String first = "first";
        MutationJournalRecord initial = new MutationJournalRecord(mutationId, owner, actor, session,
                1, fence, 1, 0, null, MutationState.PREPARED,
                MutationPlan.cursorStable(escrow), empty, first);
        assertEquals(NetworkSessionStore.PrepareResult.PREPARED, network.prepare(initial));
        assertInstanceOf(Storage.SaveResult.Success.class, network.commitPrepared(mutationId));
        CursorSettlement transition = new CursorSettlement(CursorSettlement.Kind.CURSOR_TO_VAULT,
                CursorSettlement.Stage.PLANNED, 2, escrow.projection(), SlotValue.empty(), null);
        MutationPlan settlement = MutationPlan.settlement(List.of(), escrow, transition);
        assertTrue(network.prepareSettlement(mutationId, 1, settlement, 1, 1, first, "stale-write"));
        try (var c = storage.dataSource.getConnection(); var ps = c.prepareStatement(
                "UPDATE " + storage.table("pages") + " SET data = 'newer-writer', revision = 2"
                        + " WHERE uuid = ? AND page = 1")) {
            ps.setString(1, owner.toString());
            assertEquals(1, ps.executeUpdate());
        }

        assertTrue(!network.applySettlement(mutationId, 2).success());
        assertEquals(2, pageRevision(owner));
        assertEquals(MutationState.SETTLEMENT_PREPARED,
                network.activeMutation(owner).orElseThrow().state());
    }

    private void expireLease(UUID owner) throws Exception {
        try (var c = storage.dataSource.getConnection(); var ps = c.prepareStatement(
                "UPDATE " + storage.table("player_fence")
                        + " SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            ps.setString(1, owner.toString());
            assertEquals(1, ps.executeUpdate());
        }
    }

    private long pageRevision(UUID owner) throws Exception {
        try (var c = storage.dataSource.getConnection(); var ps = c.prepareStatement(
                "SELECT revision FROM " + storage.table("pages") + " WHERE uuid = ? AND page = 1")) {
            ps.setString(1, owner.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private MutationJournalRecord mutation(
            UUID owner, UUID actor, UUID session, long sequence, long fence, long revision) {
        SlotValue item = SlotValue.fromText("item-" + sequence);
        MutationPlan plan = new MutationPlan(List.of(new SlotMutation(
                new SlotRef(SlotRef.Area.PLAYER, 3), item, SlotValue.empty(), SlotValue.empty())));
        String emptyVault = storage.emptyVault();
        return new MutationJournalRecord(UUID.randomUUID(), owner, actor, session, sequence, fence,
                1, revision, null, MutationState.PREPARED, plan, emptyVault, emptyVault);
    }

    private long granted(NetworkSessionStore.ClaimResult result) {
        return assertInstanceOf(NetworkSessionStore.ClaimResult.Granted.class, result).fence();
    }

    private static final class TestStorage extends AbstractJdbcStorage {

        private TestStorage() {}

        private TestStorage(String tablePrefix) {
            super(tablePrefix);
        }

        @Override
        protected HikariDataSource createDataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            config.setMaximumPoolSize(2);
            return new HikariDataSource(config);
        }

        @Override
        protected String insertIfAbsentSql() {
            return "INSERT IGNORE INTO " + table("pages")
                    + " (uuid, page, data, revision) VALUES (?, ?, ?, 1)";
        }

        @Override
        protected String insertFenceIfAbsentSql() {
            return "INSERT IGNORE INTO " + table("player_fence") + " (uuid, fence) VALUES (?, 0)";
        }

        @Override
        protected String backupsTableSql() {
            return """
                CREATE TABLE IF NOT EXISTS %s (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    uuid VARCHAR(36) NOT NULL,
                    page TINYINT NOT NULL,
                    revision BIGINT NOT NULL,
                    reason VARCHAR(32) NOT NULL,
                    created_at BIGINT NOT NULL,
                    data CLOB NOT NULL
                )
                """.formatted(table("backups"));
        }

        String emptyVault() {
            return serialize(new ItemStack[45]);
        }
    }
}
