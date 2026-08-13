package com.valerin.venderchest.storage;

import com.google.gson.Gson;
import com.valerin.venderchest.crossserver.MutationJournalRecord;
import com.valerin.venderchest.crossserver.MutationPlan;
import com.valerin.venderchest.crossserver.MutationState;
import com.valerin.venderchest.crossserver.CursorSettlement;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class NetworkSessionStore {

    private static final Gson GSON = new Gson();
    private static final int MAX_JOURNAL_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA_KEY = "cross-server-schema-version";

    private final AbstractJdbcStorage storage;

    public NetworkSessionStore(MysqlStorage storage) {
        this.storage = storage;
    }

    NetworkSessionStore(AbstractJdbcStorage storage) {
        this.storage = storage;
    }

    public void initSchema() throws SQLException {
        try (Connection c = storage.dataSource.getConnection(); var stmt = c.createStatement()) {
            String options = mysqlTableOptions();
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    meta_key VARCHAR(64) PRIMARY KEY,
                    meta_value VARCHAR(255) NOT NULL
                )%s
                """.formatted(storage.table("schema_meta"), options));

            Integer existingVersion = schemaVersion(c);
            if (existingVersion != null && existingVersion != SCHEMA_VERSION) {
                throw new SQLException("Unsupported cross-server schema version " + existingVersion);
            }

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) PRIMARY KEY,
                    fence BIGINT NOT NULL,
                    session_id VARCHAR(36),
                    active_mutation VARCHAR(36),
                    recovery_id VARCHAR(36),
                    guard_state VARCHAR(16) NOT NULL DEFAULT 'FREE',
                    lease_until TIMESTAMP(3),
                    updated_at BIGINT NOT NULL DEFAULT 0
                )%s
                """.formatted(storage.table("player_fence"), options));
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    mutation_id VARCHAR(36) PRIMARY KEY,
                    owner_uuid VARCHAR(36) NOT NULL,
                    actor_uuid VARCHAR(36) NOT NULL,
                    session_id VARCHAR(36) NOT NULL,
                    mutation_sequence BIGINT NOT NULL,
                    fence BIGINT NOT NULL,
                    page TINYINT NOT NULL,
                    base_revision BIGINT NOT NULL,
                    new_revision BIGINT,
                    state VARCHAR(20) NOT NULL,
                    player_plan MEDIUMTEXT NOT NULL,
                    vault_before MEDIUMTEXT NOT NULL,
                    vault_after MEDIUMTEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE (session_id, mutation_sequence)
                )%s
                """.formatted(storage.table("mutation_journal"), options));

            validateColumns(c, storage.table("schema_meta"), Set.of("meta_key", "meta_value"));
            validateColumns(c, storage.table("player_fence"), Set.of(
                    "uuid", "fence", "session_id", "active_mutation", "recovery_id",
                    "guard_state", "lease_until", "updated_at"));
            validateColumns(c, storage.table("mutation_journal"), Set.of(
                    "mutation_id", "owner_uuid", "actor_uuid", "session_id", "mutation_sequence",
                    "fence", "page", "base_revision", "new_revision", "state", "player_plan",
                    "vault_before", "vault_after", "created_at", "updated_at"));
            if (storage instanceof MysqlStorage) validateMysqlTables(c);

            if (existingVersion == null) {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT IGNORE INTO " + storage.table("schema_meta")
                                + " (meta_key, meta_value) VALUES (?, ?)")) {
                    insert.setString(1, SCHEMA_KEY);
                    insert.setString(2, Integer.toString(SCHEMA_VERSION));
                    insert.executeUpdate();
                }
                Integer installed = schemaVersion(c);
                if (!Integer.valueOf(SCHEMA_VERSION).equals(installed)) {
                    throw new SQLException("Could not install cross-server schema version");
                }
            }
        }
    }

    public boolean hasActiveState() throws SQLException {
        try (Connection c = storage.dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + storage.table("player_fence")
                            + " WHERE session_id IS NOT NULL OR active_mutation IS NOT NULL"
                            + " OR guard_state <> 'FREE' LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return true;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + storage.table("mutation_journal")
                            + " WHERE state NOT IN ('ACKED', 'ABORTED') LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Read-only compatibility gate for journals created by the superseded escrow runtime. */
    public boolean hasNonTerminalMutation() throws SQLException {
        try (Connection c = storage.dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + storage.table("player_fence")
                            + " WHERE active_mutation IS NOT NULL LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return true;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + storage.table("mutation_journal")
                            + " WHERE state NOT IN ('ACKED', 'ABORTED') LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Integer schemaVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT meta_value FROM " + storage.table("schema_meta") + " WHERE meta_key = ?")) {
            ps.setString(1, SCHEMA_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                try {
                    return Integer.parseInt(rs.getString(1));
                } catch (NumberFormatException e) {
                    throw new SQLException("Invalid cross-server schema version");
                }
            }
        }
    }

    private String mysqlTableOptions() {
        return storage instanceof MysqlStorage
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
    }

    private void validateColumns(Connection c, String table, Set<String> required) throws SQLException {
        DatabaseMetaData metadata = c.getMetaData();
        Set<String> actual = new HashSet<>();
        for (String candidate : List.of(table, table.toUpperCase())) {
            try (ResultSet rs = metadata.getColumns(c.getCatalog(), null, candidate, null)) {
                while (rs.next()) actual.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            if (!actual.isEmpty()) break;
        }
        if (!actual.containsAll(required)) {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(actual);
            throw new SQLException("Cross-server table " + table + " is missing columns " + missing);
        }
    }

    private void validateMysqlTables(Connection c) throws SQLException {
        String sql = "SELECT ENGINE, TABLE_COLLATION FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        for (String table : Set.of(storage.table("schema_meta"), storage.table("player_fence"),
                storage.table("mutation_journal"))) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Missing cross-server table " + table);
                    if (!"InnoDB".equalsIgnoreCase(rs.getString("ENGINE"))) {
                        throw new SQLException("Cross-server table " + table + " must use InnoDB");
                    }
                    String collation = rs.getString("TABLE_COLLATION");
                    if (collation == null || !collation.toLowerCase().startsWith("utf8mb4_")) {
                        throw new SQLException("Cross-server table " + table + " must use utf8mb4 collation");
                    }
                }
            }
        }
    }

    public ClaimResult claimSession(UUID ownerUuid, UUID sessionId, int leaseSeconds) {
        if (leaseSeconds < 1) return new ClaimResult.Failure("DB lease must be positive");
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ensureGuardRow(c, ownerUuid);
                Guard guard = lockGuard(c, ownerUuid);
                if (guard.activeMutation() != null || "QUARANTINED".equals(guard.state())) {
                    c.rollback();
                    return new ClaimResult.RecoveryRequired(guard.activeMutation());
                }
                if (sessionId.toString().equals(guard.sessionId()) && guard.leaseLive()) {
                    c.rollback();
                    return new ClaimResult.Granted(guard.fence());
                }
                if (guard.sessionId() != null && guard.leaseLive()) {
                    c.rollback();
                    return new ClaimResult.Busy();
                }
                if (guard.fence() == Long.MAX_VALUE) throw new SQLException("Fencing token exhausted");
                long next = guard.fence() + 1;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s
                        SET fence = ?, session_id = ?, guard_state = 'ACTIVE', active_mutation = NULL,
                            lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP), updated_at = ?
                        WHERE uuid = ?
                        """.formatted(storage.table("player_fence")))) {
                    ps.setLong(1, next);
                    ps.setString(2, sessionId.toString());
                    ps.setInt(3, leaseSeconds);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.setString(5, ownerUuid.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Could not claim owner guard");
                }
                c.commit();
                return new ClaimResult.Granted(next);
            } catch (SQLException e) {
                c.rollback();
                return new ClaimResult.Failure(e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return new ClaimResult.Failure(e.getMessage());
        }
    }

    public boolean renewSession(UUID ownerUuid, UUID sessionId, long fence, int leaseSeconds) {
        if (leaseSeconds < 1) return false;
        try (Connection c = storage.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE %s
                SET lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP), updated_at = ?
                WHERE uuid = ? AND session_id = ? AND fence = ?
                  AND guard_state <> 'QUARANTINED' AND lease_until > CURRENT_TIMESTAMP
                """.formatted(storage.table("player_fence")))) {
            ps.setInt(1, leaseSeconds);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, ownerUuid.toString());
            ps.setString(4, sessionId.toString());
            ps.setLong(5, fence);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean validateSession(UUID ownerUuid, UUID sessionId, long fence) {
        try (Connection c = storage.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM %s
                WHERE uuid = ? AND session_id = ? AND fence = ?
                  AND guard_state <> 'QUARANTINED' AND lease_until > CURRENT_TIMESTAMP
                """.formatted(storage.table("player_fence")))) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, sessionId.toString());
            ps.setLong(3, fence);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public RecoveryClaim beginRecovery(UUID ownerUuid, UUID recoveryId, int leaseSeconds) {
        if (leaseSeconds < 1) return new RecoveryClaim.Failure("DB lease must be positive");
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ensureGuardRow(c, ownerUuid);
                Guard guard = lockGuard(c, ownerUuid);
                if (guard.activeMutation() == null) {
                    c.rollback();
                    return new RecoveryClaim.None();
                }
                MutationJournalRecord journal = lockJournal(c, UUID.fromString(guard.activeMutation()))
                        .orElseThrow(() -> new SQLException("Missing active journal"));
                if ("QUARANTINED".equals(guard.state()) || journal.state() == MutationState.QUARANTINED) {
                    c.rollback();
                    return new RecoveryClaim.Quarantined(journal.mutationId().toString());
                }
                if (guard.leaseLive()) {
                    c.rollback();
                    return new RecoveryClaim.Busy();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s
                        SET recovery_id = ?, guard_state = 'RECOVERING',
                            lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP), updated_at = ?
                        WHERE uuid = ? AND session_id = ? AND fence = ? AND active_mutation = ?
                          AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                          AND guard_state IN ('MUTATING', 'RECOVERING')
                        """.formatted(storage.table("player_fence")))) {
                    ps.setString(1, recoveryId.toString());
                    ps.setInt(2, leaseSeconds);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, ownerUuid.toString());
                    ps.setString(5, journal.sessionId().toString());
                    ps.setLong(6, journal.fencingToken());
                    ps.setString(7, journal.mutationId().toString());
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        return new RecoveryClaim.Busy();
                    }
                }
                c.commit();
                return new RecoveryClaim.Acquired(journal);
            } catch (Exception e) {
                c.rollback();
                return new RecoveryClaim.Failure(e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return new RecoveryClaim.Failure(e.getMessage());
        }
    }

    public boolean renewRecovery(UUID ownerUuid, UUID recoveryId, long fence, int leaseSeconds) {
        if (leaseSeconds < 1) return false;
        try (Connection c = storage.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE %s
                SET lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP), updated_at = ?
                WHERE uuid = ? AND recovery_id = ? AND fence = ? AND guard_state = 'RECOVERING'
                  AND active_mutation IS NOT NULL AND lease_until > CURRENT_TIMESTAMP
                """.formatted(storage.table("player_fence")))) {
            ps.setInt(1, leaseSeconds);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, ownerUuid.toString());
            ps.setString(4, recoveryId.toString());
            ps.setLong(5, fence);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean pauseRecovery(UUID ownerUuid, UUID recoveryId, long fence) {
        try (Connection c = storage.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE %s
                SET recovery_id = NULL, guard_state = 'MUTATING', lease_until = NULL, updated_at = ?
                WHERE uuid = ? AND recovery_id = ? AND fence = ? AND guard_state = 'RECOVERING'
                  AND active_mutation IS NOT NULL
                """.formatted(storage.table("player_fence")))) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ownerUuid.toString());
            ps.setString(3, recoveryId.toString());
            ps.setLong(4, fence);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean acknowledgeRecovery(UUID mutationId, UUID recoveryId) {
        return finishRecovery(mutationId, recoveryId, MutationState.DB_COMMITTED, MutationState.ACKED);
    }

    public boolean abortRecovery(UUID mutationId, UUID recoveryId) {
        return finishRecovery(mutationId, recoveryId, MutationState.PREPARED, MutationState.ABORTED);
    }

    public boolean prepareSettlementRecovery(UUID mutationId, UUID recoveryId, MutationPlan plan) {
        if (!plan.isSettlement() || plan.settlement().stage() != CursorSettlement.Stage.PLANNED
                || plan.settlement().nextEscrow() != null) return false;
        String payload = GSON.toJson(plan);
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                if (!ownsRecovery(guard, record, recoveryId)
                        || record.state() != MutationState.CURSOR_STABLE
                        || !record.playerPlan().isCursorStable()
                        || !record.playerPlan().escrow().equals(plan.escrow())
                        || payloadBytes(payload, record.vaultBefore(), record.vaultAfter())
                        > MAX_JOURNAL_PAYLOAD_BYTES) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = 'SETTLEMENT_PREPARED', player_plan = ?, updated_at = ?
                        WHERE mutation_id = ? AND state = 'CURSOR_STABLE' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, payload);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, mutationId.toString());
                    ps.setString(4, GSON.toJson(record.playerPlan()));
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery settlement lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public boolean abortSettlementRecovery(UUID mutationId, UUID recoveryId, long opSequence) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                MutationPlan plan = record.playerPlan();
                if (!ownsRecovery(guard, record, recoveryId)
                        || record.state() != MutationState.SETTLEMENT_PREPARED
                        || !plan.isSettlement() || plan.settlement().opSequence() != opSequence
                        || plan.settlement().stage() != CursorSettlement.Stage.PLANNED) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = 'CURSOR_STABLE', player_plan = ?,
                            vault_after = vault_before, new_revision = base_revision, updated_at = ?
                        WHERE mutation_id = ? AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, GSON.toJson(MutationPlan.cursorStable(plan.escrow())));
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, mutationId.toString());
                    ps.setString(4, GSON.toJson(plan));
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery settlement abort lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public boolean completeSettlementRecovery(UUID mutationId, UUID recoveryId) {
        MutationJournalRecord preview = activeMutationRecord(mutationId).orElse(null);
        if (preview == null || !preview.playerPlan().isSettlement()
                || preview.playerPlan().settlement().stage() != CursorSettlement.Stage.VAULT_APPLIED) return false;
        if (preview.playerPlan().settlement().nextEscrow() == null) {
            return finishRecovery(mutationId, recoveryId,
                    MutationState.SETTLEMENT_PREPARED, MutationState.ACKED);
        }
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                MutationPlan plan = record.playerPlan();
                if (!ownsRecovery(guard, record, recoveryId)
                        || record.state() != MutationState.SETTLEMENT_PREPARED
                        || !plan.isSettlement()
                        || plan.settlement().stage() != CursorSettlement.Stage.VAULT_APPLIED
                        || plan.settlement().nextEscrow() == null) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = 'CURSOR_STABLE', player_plan = ?,
                            base_revision = new_revision, vault_before = vault_after, updated_at = ?
                        WHERE mutation_id = ? AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, GSON.toJson(MutationPlan.cursorStable(
                            plan.settlement().nextEscrow())));
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, mutationId.toString());
                    ps.setString(4, GSON.toJson(plan));
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery remainder lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public boolean applySettlementRecovery(UUID mutationId, UUID recoveryId, long opSequence) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                storage.lockPage(c, preview.ownerUuid(), preview.page());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                MutationPlan plan = record.playerPlan();
                if (!ownsRecovery(guard, record, recoveryId)
                        || record.state() != MutationState.SETTLEMENT_PREPARED
                        || !plan.isSettlement() || plan.settlement().opSequence() != opSequence) {
                    c.rollback();
                    return false;
                }
                if (plan.settlement().stage() == CursorSettlement.Stage.VAULT_APPLIED) {
                    c.rollback();
                    return true;
                }
                long revision = record.baseRevision();
                if (!record.vaultBefore().equals(record.vaultAfter())) {
                    Storage.SaveResult saved = storage.savePageIfRevision(c, record.ownerUuid(), record.page(),
                            record.vaultAfter(), record.baseRevision());
                    if (!(saved instanceof Storage.SaveResult.Success success)) {
                        c.rollback();
                        return false;
                    }
                    revision = success.newRevision();
                }
                CursorSettlement current = plan.settlement();
                CursorSettlement applied = new CursorSettlement(current.kind(),
                        CursorSettlement.Stage.VAULT_APPLIED, current.opSequence(),
                        current.cursorBefore(), current.cursorAfter(), current.nextEscrow());
                MutationPlan appliedPlan = MutationPlan.settlement(plan.playerSlots(), plan.escrow(), applied);
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET player_plan = ?, new_revision = ?, updated_at = ?
                        WHERE mutation_id = ? AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, GSON.toJson(appliedPlan));
                    ps.setLong(2, revision);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, mutationId.toString());
                    ps.setString(5, GSON.toJson(plan));
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery vault apply lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public boolean quarantineRecovery(UUID mutationId, UUID recoveryId) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                if (!ownsRecovery(guard, record, recoveryId)) {
                    c.rollback();
                    return false;
                }
                quarantine(c, record, "recovery-diverged");
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET recovery_id = NULL, lease_until = NULL
                        WHERE uuid = ? AND recovery_id = ? AND fence = ?
                          AND active_mutation = ? AND guard_state = 'QUARANTINED'
                        """.formatted(storage.table("player_fence")))) {
                    ps.setString(1, record.ownerUuid().toString());
                    ps.setString(2, recoveryId.toString());
                    ps.setLong(3, record.fencingToken());
                    ps.setString(4, mutationId.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery quarantine lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public PrepareResult prepare(MutationJournalRecord record) {
        if (record.state() != MutationState.PREPARED) throw new IllegalArgumentException("journal must start PREPARED");
        String playerPlan = GSON.toJson(record.playerPlan());
        if (payloadBytes(playerPlan, record.vaultBefore(), record.vaultAfter()) > MAX_JOURNAL_PAYLOAD_BYTES) {
            return PrepareResult.PAYLOAD_TOO_LARGE;
        }
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Guard guard = lockGuard(c, record.ownerUuid());
                if ("QUARANTINED".equals(guard.state())) {
                    c.rollback();
                    return PrepareResult.QUARANTINED;
                }
                if (guard.fence() != record.fencingToken()
                        || !record.sessionId().toString().equals(guard.sessionId())) {
                    c.rollback();
                    return PrepareResult.STALE_FENCE;
                }
                if (guard.activeMutation() != null) {
                    MutationJournalRecord existing = loadJournal(c, guard.activeMutation()).orElse(null);
                    c.rollback();
                    return record.equals(existing) ? PrepareResult.REPLAY : PrepareResult.BUSY;
                }
                if (!guard.leaseLive() || !"ACTIVE".equals(guard.state())) {
                    c.rollback();
                    return PrepareResult.STALE_FENCE;
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO %s
                        (mutation_id, owner_uuid, actor_uuid, session_id, mutation_sequence, fence,
                         page, base_revision, new_revision, state, player_plan, vault_before,
                         vault_after, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PREPARED', ?, ?, ?, ?, ?)
                        """.formatted(storage.table("mutation_journal")))) {
                    bindJournal(ps, record, playerPlan, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s
                        SET active_mutation = ?, guard_state = 'MUTATING', updated_at = ?
                        WHERE uuid = ? AND session_id = ? AND fence = ? AND active_mutation IS NULL
                          AND guard_state = 'ACTIVE' AND lease_until > CURRENT_TIMESTAMP
                        """.formatted(storage.table("player_fence")))) {
                    ps.setString(1, record.mutationId().toString());
                    ps.setLong(2, now);
                    ps.setString(3, record.ownerUuid().toString());
                    ps.setString(4, record.sessionId().toString());
                    ps.setLong(5, record.fencingToken());
                    if (ps.executeUpdate() != 1) throw new SQLException("Owner guard changed during prepare");
                }
                c.commit();
                return PrepareResult.PREPARED;
            } catch (SQLException e) {
                c.rollback();
                return PrepareResult.FAILURE;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return PrepareResult.FAILURE;
        }
    }

    public Storage.SaveResult commitPrepared(UUID mutationId) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow(
                        () -> new SQLException("Unknown mutation " + mutationId));
                Guard guard = lockGuard(c, preview.ownerUuid());
                storage.lockPage(c, preview.ownerUuid(), preview.page());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow(
                        () -> new SQLException("Unknown mutation " + mutationId));
                if (record.state() == MutationState.DB_COMMITTED
                        || record.state() == MutationState.CURSOR_STABLE
                        || record.state() == MutationState.ACKED) {
                    c.rollback();
                    return new Storage.SaveResult.Success(Objects.requireNonNull(record.newRevision()));
                }
                if (record.state() != MutationState.PREPARED) {
                    c.rollback();
                    return new Storage.SaveResult.Failure("Mutation is " + record.state());
                }
                if (guard.fence() != record.fencingToken()
                        || !record.sessionId().toString().equals(guard.sessionId())
                        || !record.mutationId().toString().equals(guard.activeMutation())
                        || !"MUTATING".equals(guard.state())) {
                    quarantine(c, record, "stale-fence");
                    c.commit();
                    return new Storage.SaveResult.StaleFence(guard.fence());
                }
                Storage.SaveResult result = record.playerPlan().isCursorStable()
                        && record.vaultBefore().equals(record.vaultAfter())
                        ? new Storage.SaveResult.Success(record.baseRevision())
                        : storage.savePageIfRevision(c, record.ownerUuid(), record.page(),
                        record.vaultAfter(), record.baseRevision());
                if (result instanceof Storage.SaveResult.Success success) {
                    MutationState committedState = record.playerPlan().isCursorStable()
                            ? MutationState.CURSOR_STABLE : MutationState.DB_COMMITTED;
                    String transition = committedState == MutationState.CURSOR_STABLE ? """
                            UPDATE %s SET state = ?, new_revision = ?, base_revision = ?,
                                vault_before = vault_after, updated_at = ?
                            WHERE mutation_id = ? AND state = 'PREPARED'
                            """.formatted(storage.table("mutation_journal")) : """
                            UPDATE %s SET state = ?, new_revision = ?, updated_at = ?
                            WHERE mutation_id = ? AND state = 'PREPARED'
                            """.formatted(storage.table("mutation_journal"));
                    try (PreparedStatement ps = c.prepareStatement(transition)) {
                        ps.setString(1, committedState.name());
                        ps.setLong(2, success.newRevision());
                        int next = 3;
                        if (committedState == MutationState.CURSOR_STABLE) {
                            ps.setLong(next++, success.newRevision());
                        }
                        ps.setLong(next++, System.currentTimeMillis());
                        ps.setString(next, mutationId.toString());
                        if (ps.executeUpdate() != 1) throw new SQLException("Journal commit transition failed");
                    }
                    c.commit();
                    return result;
                }
                c.rollback();
                return result;
            } catch (SQLException e) {
                c.rollback();
                return new Storage.SaveResult.Failure(e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return new Storage.SaveResult.Failure(e.getMessage());
        }
    }

    public boolean acknowledge(UUID mutationId) {
        return finish(mutationId, MutationState.DB_COMMITTED, MutationState.ACKED);
    }

    public boolean prepareSettlement(
            UUID mutationId,
            long expectedOpSequence,
            MutationPlan plan,
            int page,
            long baseRevision,
            String vaultBefore,
            String vaultAfter
    ) {
        if (!plan.isSettlement() || plan.escrow().opSequence() != expectedOpSequence
                || plan.settlement().opSequence() != expectedOpSequence + 1) return false;
        String payload = GSON.toJson(plan);
        if (payloadBytes(payload, vaultBefore, vaultAfter) > MAX_JOURNAL_PAYLOAD_BYTES) return false;
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                if (!ownsActiveMutation(guard, record)
                        || record.state() != MutationState.CURSOR_STABLE
                        || !record.playerPlan().isCursorStable()
                        || record.playerPlan().escrow().opSequence() != expectedOpSequence
                        || !record.playerPlan().escrow().equals(plan.escrow())) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = 'SETTLEMENT_PREPARED', player_plan = ?, page = ?,
                            base_revision = ?, new_revision = NULL, vault_before = ?, vault_after = ?, updated_at = ?
                        WHERE mutation_id = ? AND owner_uuid = ? AND session_id = ? AND fence = ?
                          AND state = 'CURSOR_STABLE' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, payload);
                    ps.setInt(2, page);
                    ps.setLong(3, baseRevision);
                    ps.setString(4, vaultBefore);
                    ps.setString(5, vaultAfter);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.setString(7, mutationId.toString());
                    ps.setString(8, record.ownerUuid().toString());
                    ps.setString(9, record.sessionId().toString());
                    ps.setLong(10, record.fencingToken());
                    ps.setString(11, GSON.toJson(record.playerPlan()));
                    if (ps.executeUpdate() != 1) throw new SQLException("Settlement prepare lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public boolean abortSettlement(UUID mutationId, long opSequence) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                MutationPlan plan = record.playerPlan();
                if (!ownsActiveMutation(guard, record)
                        || record.state() != MutationState.SETTLEMENT_PREPARED
                        || !plan.isSettlement() || plan.settlement().stage() != CursorSettlement.Stage.PLANNED
                        || plan.settlement().opSequence() != opSequence) {
                    c.rollback();
                    return false;
                }
                String stable = GSON.toJson(MutationPlan.cursorStable(plan.escrow()));
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = 'CURSOR_STABLE', player_plan = ?, vault_after = vault_before,
                            new_revision = base_revision, updated_at = ?
                        WHERE mutation_id = ? AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, stable);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, mutationId.toString());
                    ps.setString(4, GSON.toJson(plan));
                    if (ps.executeUpdate() != 1) throw new SQLException("Settlement abort lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public SettlementResult applySettlement(UUID mutationId, long opSequence) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                storage.lockPage(c, preview.ownerUuid(), preview.page());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                MutationPlan plan = record.playerPlan();
                if (!ownsActiveMutation(guard, record)
                        || record.state() != MutationState.SETTLEMENT_PREPARED
                        || !plan.isSettlement() || plan.settlement().stage() != CursorSettlement.Stage.PLANNED
                        || plan.settlement().opSequence() != opSequence) {
                    c.rollback();
                    return SettlementResult.failure();
                }
                long revision = record.baseRevision();
                if (!record.vaultBefore().equals(record.vaultAfter())) {
                    Storage.SaveResult saved = storage.savePageIfRevision(c, record.ownerUuid(), record.page(),
                            record.vaultAfter(), record.baseRevision());
                    if (!(saved instanceof Storage.SaveResult.Success success)) {
                        c.rollback();
                        return SettlementResult.failure();
                    }
                    revision = success.newRevision();
                }
                CursorSettlement current = plan.settlement();
                CursorSettlement applied = new CursorSettlement(current.kind(),
                        CursorSettlement.Stage.VAULT_APPLIED, current.opSequence(), current.cursorBefore(),
                        current.cursorAfter(), current.nextEscrow());
                MutationPlan appliedPlan = MutationPlan.settlement(plan.playerSlots(), plan.escrow(), applied);
                String payload = GSON.toJson(appliedPlan);
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET player_plan = ?, new_revision = ?, updated_at = ?
                        WHERE mutation_id = ? AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, payload);
                    ps.setLong(2, revision);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, mutationId.toString());
                    ps.setString(5, GSON.toJson(plan));
                    if (ps.executeUpdate() != 1) throw new SQLException("Settlement apply lost ownership");
                }
                c.commit();
                return new SettlementResult(true, revision, appliedPlan,
                        record.vaultBefore(), record.vaultAfter());
            } catch (Exception error) {
                c.rollback();
                return SettlementResult.failure();
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return SettlementResult.failure();
        }
    }

    public boolean completeSettlement(UUID mutationId, long opSequence) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                MutationPlan plan = record.playerPlan();
                if (!ownsActiveMutation(guard, record)
                        || record.state() != MutationState.SETTLEMENT_PREPARED
                        || !plan.isSettlement() || plan.settlement().stage() != CursorSettlement.Stage.VAULT_APPLIED
                        || plan.settlement().opSequence() != opSequence) {
                    c.rollback();
                    return false;
                }
                CursorEscrowTarget target = target(plan);
                long now = System.currentTimeMillis();
                if (target.terminal()) {
                    finishSettlementJournal(c, record, plan, now);
                    clearActiveMutation(c, record, now);
                } else {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE %s SET state = 'CURSOR_STABLE', player_plan = ?,
                                base_revision = new_revision, vault_before = vault_after, updated_at = ?
                            WHERE mutation_id = ? AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                            """.formatted(storage.table("mutation_journal")))) {
                        ps.setString(1, GSON.toJson(MutationPlan.cursorStable(target.escrow())));
                        ps.setLong(2, now);
                        ps.setString(3, mutationId.toString());
                        ps.setString(4, GSON.toJson(plan));
                        if (ps.executeUpdate() != 1) throw new SQLException("Settlement completion lost ownership");
                    }
                }
                c.commit();
                return true;
            } catch (Exception error) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException error) {
            return false;
        }
    }

    private boolean ownsActiveMutation(Guard guard, MutationJournalRecord record) {
        return guard.fence() == record.fencingToken()
                && record.sessionId().toString().equals(guard.sessionId())
                && record.mutationId().toString().equals(guard.activeMutation())
                && "MUTATING".equals(guard.state());
    }

    private Optional<MutationJournalRecord> activeMutationRecord(UUID mutationId) {
        try (Connection c = storage.dataSource.getConnection()) {
            return loadJournal(c, mutationId.toString());
        } catch (SQLException error) {
            return Optional.empty();
        }
    }

    private CursorEscrowTarget target(MutationPlan plan) {
        return new CursorEscrowTarget(plan.settlement().nextEscrow() == null,
                plan.settlement().nextEscrow());
    }

    private void finishSettlementJournal(Connection c, MutationJournalRecord record,
                                         MutationPlan plan, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE %s SET state = 'ACKED', updated_at = ?
                WHERE mutation_id = ? AND owner_uuid = ? AND session_id = ? AND fence = ?
                  AND state = 'SETTLEMENT_PREPARED' AND player_plan = ?
                """.formatted(storage.table("mutation_journal")))) {
            ps.setLong(1, now);
            ps.setString(2, record.mutationId().toString());
            ps.setString(3, record.ownerUuid().toString());
            ps.setString(4, record.sessionId().toString());
            ps.setLong(5, record.fencingToken());
            ps.setString(6, GSON.toJson(plan));
            if (ps.executeUpdate() != 1) throw new SQLException("Settlement ACK lost ownership");
        }
    }

    private void clearActiveMutation(Connection c, MutationJournalRecord record, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE %s SET active_mutation = NULL, guard_state = 'ACTIVE', updated_at = ?
                WHERE uuid = ? AND session_id = ? AND fence = ? AND active_mutation = ?
                  AND guard_state = 'MUTATING'
                """.formatted(storage.table("player_fence")))) {
            ps.setLong(1, now);
            ps.setString(2, record.ownerUuid().toString());
            ps.setString(3, record.sessionId().toString());
            ps.setLong(4, record.fencingToken());
            ps.setString(5, record.mutationId().toString());
            if (ps.executeUpdate() != 1) throw new SQLException("Settlement guard ACK lost ownership");
        }
    }

    private record CursorEscrowTarget(boolean terminal, com.valerin.venderchest.crossserver.CursorEscrow escrow) {}

    public record SettlementResult(boolean success, long newRevision, MutationPlan plan,
                                   String vaultBefore, String vaultAfter) {
        static SettlementResult failure() { return new SettlementResult(false, -1, null, null, null); }
    }

    public boolean abortPrepared(UUID mutationId) {
        return finish(mutationId, MutationState.PREPARED, MutationState.ABORTED);
    }

    public void quarantine(UUID mutationId) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                if (!ownsActiveMutation(guard, record)) {
                    c.rollback();
                    return;
                }
                quarantine(c, record, "player-diverged");
                c.commit();
            } catch (Exception e) {
                c.rollback();
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
        }
    }

    public Optional<MutationJournalRecord> activeMutation(UUID ownerUuid) {
        try (Connection c = storage.dataSource.getConnection()) {
            Guard guard = guard(c, ownerUuid);
            return guard.activeMutation() == null ? Optional.empty() : loadJournal(c, guard.activeMutation());
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public boolean releaseSession(UUID ownerUuid, UUID sessionId, long fence) {
        try (Connection c = storage.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE %s
                SET session_id = NULL, guard_state = 'FREE', lease_until = NULL, updated_at = ?
                WHERE uuid = ? AND session_id = ? AND fence = ? AND active_mutation IS NULL
                  AND guard_state = 'ACTIVE'
                """.formatted(storage.table("player_fence")))) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ownerUuid.toString());
            ps.setString(3, sessionId.toString());
            ps.setLong(4, fence);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean finish(UUID mutationId, MutationState expected, MutationState terminal) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                if (preview.state() == terminal) {
                    c.rollback();
                    return true;
                }
                if (preview.state() != expected) {
                    c.rollback();
                    return false;
                }
                long now = System.currentTimeMillis();
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                if (record.state() != expected) {
                    c.rollback();
                    return record.state() == terminal;
                }
                if (guard.fence() != record.fencingToken()
                        || !record.sessionId().toString().equals(guard.sessionId())
                        || !record.mutationId().toString().equals(guard.activeMutation())) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = ?, updated_at = ?
                        WHERE mutation_id = ? AND owner_uuid = ? AND session_id = ? AND fence = ? AND state = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, terminal.name());
                    ps.setLong(2, now);
                    ps.setString(3, mutationId.toString());
                    ps.setString(4, record.ownerUuid().toString());
                    ps.setString(5, record.sessionId().toString());
                    ps.setLong(6, record.fencingToken());
                    ps.setString(7, expected.name());
                    if (ps.executeUpdate() != 1) throw new SQLException("Journal terminal transition lost ownership");
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s
                        SET active_mutation = NULL, guard_state = 'ACTIVE', updated_at = ?
                        WHERE uuid = ? AND session_id = ? AND fence = ?
                          AND active_mutation = ? AND guard_state = 'MUTATING'
                        """.formatted(storage.table("player_fence")))) {
                    ps.setLong(1, now);
                    ps.setString(2, record.ownerUuid().toString());
                    ps.setString(3, record.sessionId().toString());
                    ps.setLong(4, record.fencingToken());
                    ps.setString(5, mutationId.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Owner guard no longer owns mutation");
                }
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean finishRecovery(
            UUID mutationId, UUID recoveryId, MutationState expected, MutationState terminal) {
        try (Connection c = storage.dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                MutationJournalRecord preview = loadJournal(c, mutationId.toString()).orElseThrow();
                if (preview.state() == terminal) {
                    c.rollback();
                    return true;
                }
                Guard guard = lockGuard(c, preview.ownerUuid());
                MutationJournalRecord record = lockJournal(c, mutationId).orElseThrow();
                if (record.state() != expected || !ownsRecovery(guard, record, recoveryId)) {
                    c.rollback();
                    return false;
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s SET state = ?, updated_at = ?
                        WHERE mutation_id = ? AND owner_uuid = ? AND session_id = ? AND fence = ? AND state = ?
                        """.formatted(storage.table("mutation_journal")))) {
                    ps.setString(1, terminal.name());
                    ps.setLong(2, now);
                    ps.setString(3, mutationId.toString());
                    ps.setString(4, record.ownerUuid().toString());
                    ps.setString(5, record.sessionId().toString());
                    ps.setLong(6, record.fencingToken());
                    ps.setString(7, expected.name());
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery terminal transition lost ownership");
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE %s
                        SET active_mutation = NULL, session_id = NULL, recovery_id = NULL,
                            guard_state = 'FREE', lease_until = NULL, updated_at = ?
                        WHERE uuid = ? AND session_id = ? AND fence = ? AND active_mutation = ?
                          AND recovery_id = ? AND guard_state = 'RECOVERING'
                        """.formatted(storage.table("player_fence")))) {
                    ps.setLong(1, now);
                    ps.setString(2, record.ownerUuid().toString());
                    ps.setString(3, record.sessionId().toString());
                    ps.setLong(4, record.fencingToken());
                    ps.setString(5, mutationId.toString());
                    ps.setString(6, recoveryId.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Recovery guard transition lost ownership");
                }
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                return false;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean ownsRecovery(Guard guard, MutationJournalRecord record, UUID recoveryId) {
        return guard.fence() == record.fencingToken()
                && record.sessionId().toString().equals(guard.sessionId())
                && record.mutationId().toString().equals(guard.activeMutation())
                && recoveryId.toString().equals(guard.recoveryId())
                && "RECOVERING".equals(guard.state()) && guard.leaseLive();
    }

    private void quarantine(Connection c, MutationJournalRecord record, String reason) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE %s SET state = 'QUARANTINED', updated_at = ?
                WHERE mutation_id = ? AND owner_uuid = ? AND session_id = ? AND fence = ?
                  AND state NOT IN ('ACKED', 'ABORTED')
                """.formatted(storage.table("mutation_journal")))) {
            ps.setLong(1, now);
            ps.setString(2, record.mutationId().toString());
            ps.setString(3, record.ownerUuid().toString());
            ps.setString(4, record.sessionId().toString());
            ps.setLong(5, record.fencingToken());
            if (ps.executeUpdate() != 1) throw new SQLException("Journal quarantine lost ownership");
        }
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE %s
                SET guard_state = 'QUARANTINED', updated_at = ?
                WHERE uuid = ? AND session_id = ? AND fence = ? AND active_mutation = ?
                """.formatted(storage.table("player_fence")))) {
            ps.setLong(1, now);
            ps.setString(2, record.ownerUuid().toString());
            ps.setString(3, record.sessionId().toString());
            ps.setLong(4, record.fencingToken());
            ps.setString(5, record.mutationId().toString());
            if (ps.executeUpdate() != 1) throw new SQLException("Owner quarantine lost ownership");
        }
    }

    private void ensureGuardRow(Connection c, UUID ownerUuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(storage.insertFenceIfAbsentSql())) {
            ps.setString(1, ownerUuid.toString());
            ps.executeUpdate();
        }
    }

    private Guard lockGuard(Connection c, UUID ownerUuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT fence, session_id, active_mutation, recovery_id, guard_state,
                       CASE WHEN lease_until > CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS lease_live
                FROM %s WHERE uuid = ? FOR UPDATE
                """.formatted(storage.table("player_fence")))) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Missing owner guard");
                return readGuard(rs);
            }
        }
    }

    private Guard guard(Connection c, UUID ownerUuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT fence, session_id, active_mutation, recovery_id, guard_state,
                       CASE WHEN lease_until > CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS lease_live
                FROM %s WHERE uuid = ?
                """.formatted(storage.table("player_fence")))) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Guard(0, null, null, null, "FREE", false);
                return readGuard(rs);
            }
        }
    }

    private Guard readGuard(ResultSet rs) throws SQLException {
        return new Guard(rs.getLong("fence"), rs.getString("session_id"),
                rs.getString("active_mutation"), rs.getString("recovery_id"),
                rs.getString("guard_state"), rs.getInt("lease_live") == 1);
    }

    private Optional<MutationJournalRecord> lockJournal(Connection c, UUID mutationId) throws SQLException {
        return loadJournal(c, mutationId.toString(), true);
    }

    private Optional<MutationJournalRecord> loadJournal(Connection c, String mutationId) throws SQLException {
        return loadJournal(c, mutationId, false);
    }

    private Optional<MutationJournalRecord> loadJournal(Connection c, String mutationId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM " + storage.table("mutation_journal")
                + " WHERE mutation_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mutationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readJournal(rs)) : Optional.empty();
            }
        }
    }

    private MutationJournalRecord readJournal(ResultSet rs) throws SQLException {
        long newRevision = rs.getLong("new_revision");
        Long nullableRevision = rs.wasNull() ? null : newRevision;
        return new MutationJournalRecord(
                UUID.fromString(rs.getString("mutation_id")),
                UUID.fromString(rs.getString("owner_uuid")),
                UUID.fromString(rs.getString("actor_uuid")),
                UUID.fromString(rs.getString("session_id")),
                rs.getLong("mutation_sequence"),
                rs.getLong("fence"),
                rs.getInt("page"),
                rs.getLong("base_revision"),
                nullableRevision,
                MutationState.valueOf(rs.getString("state")),
                GSON.fromJson(rs.getString("player_plan"), MutationPlan.class),
                rs.getString("vault_before"),
                rs.getString("vault_after")
        );
    }

    private void bindJournal(
            PreparedStatement ps, MutationJournalRecord record, String playerPlan, long now) throws SQLException {
        ps.setString(1, record.mutationId().toString());
        ps.setString(2, record.ownerUuid().toString());
        ps.setString(3, record.actorUuid().toString());
        ps.setString(4, record.sessionId().toString());
        ps.setLong(5, record.sequence());
        ps.setLong(6, record.fencingToken());
        ps.setInt(7, record.page());
        ps.setLong(8, record.baseRevision());
        ps.setString(9, playerPlan);
        ps.setString(10, record.vaultBefore());
        ps.setString(11, record.vaultAfter());
        ps.setLong(12, now);
        ps.setLong(13, now);
    }

    private long payloadBytes(String... values) {
        long bytes = 0;
        for (String value : values) bytes += value.getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    private record Guard(long fence, String sessionId, String activeMutation,
                         String recoveryId, String state, boolean leaseLive) {}

    public sealed interface ClaimResult {
        record Granted(long fence) implements ClaimResult {}
        record Busy() implements ClaimResult {}
        record RecoveryRequired(String mutationId) implements ClaimResult {}
        record Failure(String reason) implements ClaimResult {}
    }

    public sealed interface RecoveryClaim {
        record Acquired(MutationJournalRecord journal) implements RecoveryClaim {}
        record None() implements RecoveryClaim {}
        record Busy() implements RecoveryClaim {}
        record Quarantined(String mutationId) implements RecoveryClaim {}
        record Failure(String reason) implements RecoveryClaim {}
    }

    public enum PrepareResult {
        PREPARED,
        REPLAY,
        BUSY,
        STALE_FENCE,
        QUARANTINED,
        PAYLOAD_TOO_LARGE,
        FAILURE
    }
}
