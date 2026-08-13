package com.valerin.venderchest.storage;

import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface Storage {

    void init() throws SQLException;

    /**
     * Returns array[45]; null slots = empty.
     * Convenience wrapper around {@link #loadPageWithRevision(UUID, int)} for callers that
     * don't need optimistic concurrency (e.g. migration).
     */
    ItemStack[] loadPage(UUID uuid, int page);

    /**
     * Convenience wrapper that loads the current revision and retries
     * {@link #savePageIfRevision(UUID, int, ItemStack[], long)} on conflict a bounded number of
     * times. Prefer {@link #savePageIfRevision} directly when a session already knows the
     * revision it loaded against.
     */
    void savePage(UUID uuid, int page, ItemStack[] items);

    /**
     * A page's content together with the persisted revision it was read at.
     * Revision 0 means either no row yet or an existing row upgraded from the pre-revision schema.
     */
    record PageRecord(ItemStack[] items, long revision) {}

    /** Outcome of an optimistic-concurrency save attempt. */
    sealed interface SaveResult permits SaveResult.Success, SaveResult.Conflict, SaveResult.StaleFence, SaveResult.Failure {
        record Success(long newRevision) implements SaveResult {}
        /** The persisted revision no longer matched what the caller expected; nothing was written. */
        record Conflict(long currentRevision) implements SaveResult {}
        /** A newer cross-server lease advanced the owner's durable fencing token. */
        record StaleFence(long currentFence) implements SaveResult {}
        /** The store could not confirm whether the operation completed. */
        record Failure(String reason) implements SaveResult {}
    }

    /** Loads a page together with its current persisted revision. */
    PageRecord loadPageWithRevision(UUID uuid, int page);

    /**
     * Compare-and-swap save: writes {@code items} only if the persisted revision still equals
     * {@code expectedRevision}. Revision 0 safely covers both a missing row and an existing row
     * upgraded from the pre-revision schema. On success the new persisted revision is
     * {@code expectedRevision + 1}. Never overwrites a row whose revision has already moved past
     * what the caller last observed.
     */
    SaveResult savePageIfRevision(UUID uuid, int page, ItemStack[] items, long expectedRevision);

    /** Advances and returns the durable, owner-wide fencing token. MySQL is authoritative. */
    long advanceFencingToken(UUID uuid) throws SQLException;

    /** Revision CAS guarded by the durable owner-wide fencing token in the same DB transaction. */
    SaveResult savePageIfRevisionAndFence(
            UUID uuid, int page, ItemStack[] items, long expectedRevision, long fencingToken);

    void clearPage(UUID uuid, int page);

    // ── Backups / revision history ──────────────────────────────────────────

    /** Metadata for one stored backup row - never carries item data (loaded separately, on demand). */
    record BackupRecord(int id, UUID uuid, int page, long revision, String reason, long createdAtMillis) {}

    /** Inserts a new backup row, returning its generated id. */
    int saveBackup(UUID uuid, int page, long revision, String reason, ItemStack[] items);

    /** Deletes all but the most recent {@code keep} backups for this (uuid, page). */
    void pruneBackups(UUID uuid, int page, int keep);

    /** All backups for a player across every page, most recent first. */
    List<BackupRecord> listBackups(UUID uuid);

    /** Null if no such backup exists. */
    BackupRecord getBackup(int id);

    /** Null if no such backup exists. */
    ItemStack[] loadBackupItems(int id);

    /** Count of pages that have at least one item. */
    int countUsedPages(UUID uuid);

    /** Returns page → non-empty slot count for all saved pages of this player. */
    java.util.Map<Integer, Integer> countPageItems(UUID uuid);

    // ── Extra vaults (purchased separately, stacks on top of VIP permission) ──

    int getExtraPages(UUID uuid);

    void addExtraPages(UUID uuid, int amount);

    /** Removes amount, floors at 0. */
    void removeExtraPages(UUID uuid, int amount);

    void setExtraPages(UUID uuid, int amount);

    // ── Migration tracking ──

    boolean isMigrated(UUID uuid, String type);

    void markMigrated(UUID uuid, String type);

    void unmarkMigrated(UUID uuid, String type);

    void close();
}
