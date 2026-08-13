package com.valerin.venderchest.session;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriterExclusionTest {

    @Test
    void writerAndFenceAreOneConstructionInvariant() {
        assertThrows(IllegalArgumentException.class, () -> new VaultSession(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1", Instant.now(),
                VaultWriter.CROSS_SERVER, 0));
        assertThrows(IllegalArgumentException.class, () -> new VaultSession(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1", Instant.now(),
                VaultWriter.SINGLE_SERVER, 4));
    }

    @Test
    void crossServerSessionUsesSharedCasWriterWithoutLosingItsFence() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = ((OpenAttempt.Created) registry.beginOpenCrossServer(
                UUID.randomUUID(), UUID.randomUUID(), "1", UUID.randomUUID(), 9)).session();
        assertTrue(registry.activate(session.getSessionId(), 4, new Object()));
        VaultTransactionService service = new VaultTransactionService(
                null, null, registry, null, "test", false, 1);

        AtomicReference<CommitOutcome> outcome = new AtomicReference<>();
        service.commitIfActive(session, new ItemStack[0], new ItemStack[0], outcome::set);

        assertEquals(CommitOutcome.NO_CHANGE, outcome.get());
        assertEquals(9, session.getNetworkFence());
        assertEquals(SessionState.ACTIVE, session.getState());
    }

    @Test
    void singleServerNoChangeStillUsesItsOriginalWriter() {
        VaultSessionRegistry registry = new VaultSessionRegistry();
        VaultSession session = created(registry);
        assertTrue(registry.activate(session.getSessionId(), 4, new Object()));
        VaultTransactionService service = new VaultTransactionService(
                null, null, registry, null, "test", false, 1);
        AtomicReference<CommitOutcome> outcome = new AtomicReference<>();

        service.commitIfActive(session, new ItemStack[0], new ItemStack[0], outcome::set);

        assertEquals(CommitOutcome.NO_CHANGE, outcome.get());
        assertEquals(SessionState.ACTIVE, session.getState());
    }

    private VaultSession created(VaultSessionRegistry registry) {
        return ((OpenAttempt.Created) registry.beginOpen(
                UUID.randomUUID(), UUID.randomUUID(), "1")).session();
    }
}
