package com.valerin.venderchest.session;

import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.api.ConflictType;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Structured, single-line forensic logging for session lifecycle, commits, and conflicts. Never
 * logs database URLs, credentials, or full serialized item bytes — only ids, counts, slots, and
 * revisions.
 */
public final class VaultAuditLog {

    public enum Level { MINIMAL, NORMAL, VERBOSE }

    private final Logger logger;
    private volatile Level level;
    private volatile boolean warnConsoleOnConflict;
    private volatile boolean consoleEnabled;

    public VaultAuditLog(Logger logger, Level level, boolean warnConsoleOnConflict, boolean consoleEnabled) {
        this.logger = logger;
        this.level = level;
        this.warnConsoleOnConflict = warnConsoleOnConflict;
        this.consoleEnabled = consoleEnabled;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public void setWarnConsoleOnConflict(boolean warnConsoleOnConflict) {
        this.warnConsoleOnConflict = warnConsoleOnConflict;
    }

    public void setConsoleEnabled(boolean consoleEnabled) {
        this.consoleEnabled = consoleEnabled;
    }

    public void opened(VaultSession s) {
        if (!consoleEnabled || level == Level.MINIMAL) return;
        logger.info("[audit] event=session_opened session=" + s.getSessionId()
                + " owner=" + s.getOwnerUuid() + " actor=" + s.getActorUuid()
                + " vault=" + s.getVaultId() + " revision=" + s.getCurrentRevision());
    }

    public void commit(VaultSession s, long baseRevision, long newRevision, List<SlotDiff> diffs, long durationMs) {
        if (!consoleEnabled) return;
        if (level != Level.MINIMAL) {
            logger.info("[audit] event=commit session=" + s.getSessionId() + " owner=" + s.getOwnerUuid()
                    + " actor=" + s.getActorUuid() + " vault=" + s.getVaultId()
                    + " base_rev=" + baseRevision + " new_rev=" + newRevision
                    + " slots_changed=" + diffs.size() + " duration_ms=" + durationMs);
        }
        if (level == Level.VERBOSE) {
            for (SlotDiff d : diffs) {
                logger.info("[audit] event=slot_change session=" + s.getSessionId()
                        + " slot=" + d.slot() + " action=" + d.action()
                        + " amount_before=" + d.amountBefore() + " amount_after=" + d.amountAfter());
            }
        }
    }

    public void conflict(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId,
                          ConflictType type, long expectedRevision, long actualRevision) {
        if (!consoleEnabled) return;
        // Conflict handling and API events happen independently; this only controls console output.
        String line = "[audit] event=conflict type=" + type + " session=" + sessionId
                + " owner=" + ownerUuid + " actor=" + actorUuid + " vault=" + vaultId
                + " expected_rev=" + expectedRevision + " actual_rev=" + actualRevision;
        if (warnConsoleOnConflict) logger.warning(line); else logger.info(line);
    }

    public void reopenDetected(UUID ownerUuid, UUID actorUuid, String vaultId, UUID previousSessionId) {
        if (!consoleEnabled) return;
        logger.info("[audit] event=reopen_detected owner=" + ownerUuid + " actor=" + actorUuid
                + " vault=" + vaultId + " previous_session=" + previousSessionId);
    }

    public void openRejected(UUID ownerUuid, UUID actorUuid, String vaultId, UUID blockingSessionId) {
        if (!consoleEnabled) return;
        String line = "[audit] event=open_rejected owner=" + ownerUuid + " actor=" + actorUuid
                + " vault=" + vaultId + " blocking_session=" + blockingSessionId;
        if (warnConsoleOnConflict) logger.warning(line); else logger.info(line);
    }

    public void closed(VaultSession s, CloseReason reason) {
        if (!consoleEnabled || level == Level.MINIMAL) return;
        logger.info("[audit] event=session_closed session=" + s.getSessionId()
                + " owner=" + s.getOwnerUuid() + " actor=" + s.getActorUuid()
                + " vault=" + s.getVaultId() + " reason=" + reason + " last_revision=" + s.getCurrentRevision());
    }

    public void loadDuration(VaultSession s, long durationMs) {
        if (!consoleEnabled || level != Level.VERBOSE) return;
        logger.info("[audit] event=load session=" + s.getSessionId() + " owner=" + s.getOwnerUuid()
                + " vault=" + s.getVaultId() + " duration_ms=" + durationMs);
    }
}
