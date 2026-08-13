package com.valerin.venderchest.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link VaultSessionRegistry} directly - no Bukkit, no server, no database - since it is
 * the actual fix for the reported vault duplication exploit. Every scenario here mirrors one of the
 * mandatory test scenarios from the audit: normal open/close, reopen-while-active, same-tick double
 * open, packet-less close, stale-session click rejection, logout mid-session, shutdown mid-commit,
 * and the flagship reproduction of the reported exploit itself.
 */
class VaultSessionRegistryTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID actor = owner; // normal player session: actor == owner
    private final String vaultId = "1";
    private final VaultKey key = new VaultKey(owner, vaultId);

    // 1. Normal open ---------------------------------------------------------

    @Test
    void normalOpen_registersAndActivatesASingleSession() {
        VaultSessionRegistry registry = new VaultSessionRegistry();

        OpenAttempt attempt = registry.beginOpen(owner, actor, vaultId);
        assertInstanceOf(OpenAttempt.Created.class, attempt);
        VaultSession session = ((OpenAttempt.Created) attempt).session();
        assertEquals(SessionState.OPENING, session.getState());

        assertTrue(registry.activate(session.getSessionId(), 0L, new Object()));
        assertEquals(SessionState.ACTIVE, session.getState());
        assertTrue(registry.allActive().contains(session));
        assertEquals(session.getSessionId(), registry.current(key).orElseThrow().getSessionId());
    }

    // 2. Normal close and save -----------------------------------------------

    @Test
    void normalCloseAndSave_persistsThenFreesTheKey() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = createActive(registry);

        assertTrue(registry.beginCommit(session.getSessionId()));
        assertTrue(registry.completeCommit(session.getSessionId(), 1L));
        assertEquals(1L, session.getCurrentRevision());

        assertTrue(registry.close(session.getSessionId()).isPresent());
        assertEquals(SessionState.CLOSED, session.getState());
        assertTrue(registry.current(key).isEmpty());
    }

    // 3. /ec 1 while /ec 1 is still open -------------------------------------

    @Test
    void reopenWhileActive_sameActor_isSupersededNeverASecondLiveSession() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession first = createActive(registry);

        OpenAttempt second = registry.beginOpen(owner, actor, vaultId);
        assertInstanceOf(OpenAttempt.Supersede.class, second);
        assertSame(first, ((OpenAttempt.Supersede) second).previous());
        // The key still resolves to exactly the first session - a second one was never registered.
        assertEquals(first.getSessionId(), registry.current(key).orElseThrow().getSessionId());
    }

    @Test
    void reopenWhileActive_differentActor_isRejected() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession first = createActive(registry);

        UUID otherAdmin = UUID.randomUUID();
        OpenAttempt attempt = registry.beginOpen(owner, otherAdmin, vaultId);
        assertInstanceOf(OpenAttempt.Rejected.class, attempt);
        assertSame(first, ((OpenAttempt.Rejected) attempt).existing());
    }

    // 4. Two opens in the same tick ------------------------------------------

    @Test
    void twoOpensInTheSameTick_beforeEitherActivates_onlyOneSessionEverActive() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        OpenAttempt first = registry.beginOpen(owner, actor, vaultId);
        VaultSession firstSession = ((OpenAttempt.Created) first).session();

        // A second /ec 1 arrives before the first load's activate() has run.
        OpenAttempt second = registry.beginOpen(owner, actor, vaultId);
        assertInstanceOf(OpenAttempt.Supersede.class, second);

        // Nothing was loaded for the first (still OPENING) session, so the caller just closes it.
        assertTrue(registry.close(firstSession.getSessionId()).isPresent());
        OpenAttempt retry = registry.beginOpen(owner, actor, vaultId);
        assertInstanceOf(OpenAttempt.Created.class, retry);
        VaultSession secondSession = ((OpenAttempt.Created) retry).session();
        assertNotEquals(firstSession.getSessionId(), secondSession.getSessionId());

        // The first (superseded) load's late activate() must fail - it must never open a stale GUI.
        assertFalse(registry.activate(firstSession.getSessionId(), 0L, new Object()));
        assertTrue(registry.activate(secondSession.getSessionId(), 0L, new Object()));
        assertEquals(1, registry.allActive().size());
    }

    // 5. Close without a client packet ---------------------------------------

    @Test
    void closeWithoutAClientPacket_stillCommitsAndFreesTheKey() {
        // Nothing here depends on a "close packet" concept at all - any trigger (reopen, quit,
        // autosave, admin reload) can commit+close a session identically.
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = createActive(registry);

        assertTrue(registry.beginCommit(session.getSessionId()));
        assertTrue(registry.completeCommit(session.getSessionId(), 5L));
        assertTrue(registry.close(session.getSessionId()).isPresent());

        assertTrue(registry.current(key).isEmpty());
        assertTrue(registry.bySessionId(session.getSessionId()).isEmpty());
    }

    // 6. Click/event belonging to a previous session -------------------------

    @Test
    void clickFromAPreviousSession_isRejectedByIdentity() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession first = createActive(registry);
        assertTrue(registry.beginCommit(first.getSessionId()));
        assertTrue(registry.completeCommit(first.getSessionId(), 1L));
        assertTrue(registry.close(first.getSessionId()).isPresent());
        VaultSession second = createActive(registry);

        // A click event carrying the OLD sessionId must not match the registry's current session -
        // this is exactly the check GuiManager#validateSessionOrReject performs.
        boolean staleClickWouldBeAccepted = registry.current(key)
                .map(VaultSession::getSessionId)
                .filter(id -> id.equals(first.getSessionId()))
                .isPresent();
        assertFalse(staleClickWouldBeAccepted);
        assertEquals(second.getSessionId(), registry.current(key).orElseThrow().getSessionId());
    }

    @Test
    void clicksAreRejectedAsSoonAsTheSessionStartsCommitting() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        Object inventoryToken = new Object();
        VaultSession session = createActive(registry, inventoryToken);

        assertTrue(registry.isActive(session.getSessionId(), inventoryToken));
        assertTrue(registry.beginCommit(session.getSessionId()));
        assertFalse(registry.isActive(session.getSessionId(), inventoryToken),
                "the live GUI must become non-interactive before the async database save begins");
    }

    @Test
    void clicksFromAnotherInventoryInstanceAreRejected() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        Object inventoryToken = new Object();
        VaultSession session = createActive(registry, inventoryToken);

        assertFalse(registry.isActive(session.getSessionId(), new Object()));
        assertTrue(registry.isActive(session.getSessionId(), inventoryToken));
    }

    // 12. Logout during a session ---------------------------------------------

    @Test
    void logoutMidSession_commitCanOnlyHappenOnceEvenIfCloseFiresToo() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = createActive(registry);

        // The quit handler wins the race to commit.
        assertTrue(registry.beginCommit(session.getSessionId()));
        // A racing InventoryCloseEvent (processed the same tick) must not also commit it.
        assertFalse(registry.beginCommit(session.getSessionId()));

        assertTrue(registry.completeCommit(session.getSessionId(), 1L));
        assertTrue(registry.close(session.getSessionId()).isPresent());
    }

    // 13. Shutdown during a save ----------------------------------------------

    @Test
    void shutdownDuringASave_lateAsyncCallbackCannotDoubleApply() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = createActive(registry);

        assertTrue(registry.beginCommit(session.getSessionId())); // async save "in flight"
        // Shutdown forces the session closed before the async save's callback returns.
        assertTrue(registry.close(session.getSessionId()).isPresent());

        // The async save's late callback tries to finish the commit - must be a safe no-op, never
        // a crash and never a silent resurrection of a closed session.
        assertFalse(registry.completeCommit(session.getSessionId(), 1L));
        assertEquals(SessionState.CLOSED, session.getState());
    }

    // 14. API events emitted exactly once (structural guarantee) --------------

    @Test
    void commitCanOnlyBePerformedOnce_soTheCommittedEventCanOnlyFireOnce() {
        // VaultTransactionService only ever fires VaultTransactionCommittedEvent from inside the
        // branch guarded by a successful beginCommit() (see VaultTransactionService#commitIfActive).
        // This CAS is what guarantees that event can never fire twice for the same session -
        // verified here without needing a running server to dispatch the actual Bukkit event.
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = createActive(registry);

        int wins = 0;
        for (int i = 0; i < 5; i++) {
            if (registry.beginCommit(session.getSessionId())) wins++;
        }
        assertEquals(1, wins, "only one of several racing commit attempts may proceed to fire the committed event");
    }

    // 16. The reported exploit itself ------------------------------------------

    @Test
    void reportedExploit_reopenBeforeCommit_neverLetsASecondSessionObserveStaleState() {
        VaultSessionRegistry registry = new VaultSessionRegistry();

        // /ec 1
        VaultSession sessionA = createActive(registry); // loaded at revision 0

        // Player takes an item (the resulting content diff is covered by TransactionDiffEngineTest);
        // without a clean close, /ec 1 is issued again.
        OpenAttempt reopenAttempt = registry.beginOpen(owner, actor, vaultId);
        assertInstanceOf(OpenAttempt.Supersede.class, reopenAttempt,
                "must never silently create a second live session for the same vault");
        assertSame(sessionA, ((OpenAttempt.Supersede) reopenAttempt).previous());

        // The fix: the server fully commits session A (persisting the take) BEFORE a second
        // session for the same vault can ever be created.
        assertTrue(registry.beginCommit(sessionA.getSessionId()));
        assertTrue(registry.completeCommit(sessionA.getSessionId(), 1L)); // revision now 1, item gone
        assertTrue(registry.close(sessionA.getSessionId()).isPresent());

        // Only now can the reopen actually proceed.
        OpenAttempt retry = registry.beginOpen(owner, actor, vaultId);
        assertInstanceOf(OpenAttempt.Created.class, retry);
        VaultSession sessionB = ((OpenAttempt.Created) retry).session();
        assertTrue(registry.activate(sessionB.getSessionId(), 1L, new Object()));

        // Session B is guaranteed to have loaded at revision 1 - strictly after A's commit - so it
        // can never render the item A already took. It is structurally impossible for B to observe
        // revision 0 once A's commit (revision 1) has happened.
        assertEquals(1L, sessionB.getLoadedRevision());
        assertNotEquals(sessionA.getSessionId(), sessionB.getSessionId());
        assertEquals(1, registry.allActive().size(), "exactly one live session for the vault at any time");
    }

    @Test
    void crossServerPageSwitchKeepsSessionAndFenceButReplacesPageView() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        UUID sessionId = UUID.randomUUID();
        VaultSession first = ((OpenAttempt.Created) registry.beginOpenCrossServer(
                owner, actor, "1", sessionId, 6)).session();
        assertTrue(registry.activate(sessionId, 10, new Object()));

        VaultSession second = registry.switchCrossServerPage(sessionId, "2").orElseThrow();

        assertEquals(SessionState.CLOSED, first.getState());
        assertEquals(sessionId, second.getSessionId());
        assertEquals(6, second.getNetworkFence());
        assertEquals(VaultWriter.CROSS_SERVER, second.getWriter());
        assertEquals("2", second.getVaultId());
        assertTrue(registry.current(new VaultKey(owner, "1")).isEmpty());
        assertSame(second, registry.current(new VaultKey(owner, "2")).orElseThrow());
    }

    private VaultSession createActive(VaultSessionRegistry registry) {
        return createActive(registry, new Object());
    }

    private VaultSession createActive(VaultSessionRegistry registry, Object inventoryToken) {
        OpenAttempt attempt = registry.beginOpen(owner, actor, vaultId);
        VaultSession session = ((OpenAttempt.Created) attempt).session();
        assertTrue(registry.activate(session.getSessionId(), 0L, inventoryToken));
        return session;
    }
}
