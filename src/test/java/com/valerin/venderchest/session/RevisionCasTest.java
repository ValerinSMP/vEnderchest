package com.valerin.venderchest.session;

import com.valerin.venderchest.storage.Storage;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

// NOTE: these tests deliberately never construct a real ItemStack instance (only ItemStack[]
// arrays of nulls) - Paper's Material/item registry requires a live server to resolve even a
// plain `new ItemStack(Material, amount)`, which a plain JUnit run without a server has no access
// to. The revision/CAS semantics under test do not depend on item content at all, so this loses
// nothing for what is being verified here.

/**
 * Reproduces the exact compare-and-swap contract implemented by
 * {@code AbstractJdbcStorage#savePageIfRevision} (same {@link Storage.SaveResult}/{@link
 * Storage.PageRecord} types) against a tiny in-memory store, so the revision semantics - including
 * the specific "two async saves complete out of order" race - are verified deterministically
 * without a real database.
 */
class RevisionCasTest {

    // 8. Direct revision conflict ---------------------------------------------

    @Test
    void singleConflict_rejectsAStaleWrite() {
        InMemoryCasStore store = new InMemoryCasStore();
        store.saveIfRevision(new ItemStack[45], 0); // revision now 1

        Storage.SaveResult result = store.saveIfRevision(new ItemStack[45], 0); // still believes revision 0

        assertInstanceOf(Storage.SaveResult.Conflict.class, result);
        assertEquals(1, ((Storage.SaveResult.Conflict) result).currentRevision());
    }

    // 7. Two async saves completing in reverse order ---------------------------

    @Test
    void twoAsyncSaves_completingInReverseOrder_neverSilentlyOverwritesNewerState() {
        InMemoryCasStore store = new InMemoryCasStore();

        // Two sessions both load at revision 0.
        long revisionSeenByFirst = store.load().revision();
        long revisionSeenBySecond = store.load().revision();
        assertEquals(0, revisionSeenByFirst);
        assertEquals(0, revisionSeenBySecond);

        // The SECOND session's save completes first (out of order).
        Storage.SaveResult secondResult = store.saveIfRevision(new ItemStack[45], revisionSeenBySecond);
        assertInstanceOf(Storage.SaveResult.Success.class, secondResult);
        assertEquals(1, ((Storage.SaveResult.Success) secondResult).newRevision());

        // The FIRST session's save then completes, still holding the revision it originally loaded -
        // which the store has already moved past.
        Storage.SaveResult firstResult = store.saveIfRevision(new ItemStack[45], revisionSeenByFirst);
        assertInstanceOf(Storage.SaveResult.Conflict.class, firstResult,
                "a save based on a now-superseded revision must never overwrite the newer committed state");
        assertEquals(1, ((Storage.SaveResult.Conflict) firstResult).currentRevision());

        // Exactly one of the two writes actually took effect - the revision never regresses and is
        // never advanced twice for what the store believes is a single change.
        assertEquals(1, store.load().revision());
    }

    /** Mirrors AbstractJdbcStorage's CAS algorithm against an in-memory map instead of JDBC. */
    private static final class InMemoryCasStore {
        private ItemStack[] data = new ItemStack[45];
        private long revision = 0;

        synchronized Storage.PageRecord load() {
            return new Storage.PageRecord(data.clone(), revision);
        }

        synchronized Storage.SaveResult saveIfRevision(ItemStack[] items, long expectedRevision) {
            if (expectedRevision != revision) {
                return new Storage.SaveResult.Conflict(revision);
            }
            data = items.clone();
            revision++;
            return new Storage.SaveResult.Success(revision);
        }
    }
}
