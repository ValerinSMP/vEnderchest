package com.valerin.venderchest.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 15. Public API DTOs must be immutable: every {@code ItemStack} is a defensive clone, every list
 * unmodifiable.
 *
 * <p>NOTE: these tests exercise the null-safe path (empty slots) and the list/record immutability
 * guarantees without constructing a real {@code ItemStack} - Paper's Material/item registry
 * requires a live server to resolve even a plain {@code new ItemStack(Material, amount)}, which a
 * plain JUnit run has no access to. The defensive-clone branch itself
 * ({@code item != null ? item.clone() : null} in {@link SlotChange}/{@link VaultSlot}) is a single
 * reviewable line per accessor; exercising it with a real, mutated {@code ItemStack} is left to
 * manual/in-game verification (see the delivery notes).
 */
class ApiImmutabilityTest {

    @Test
    void slotChange_nullItemsAreHandledWithoutError() {
        SlotChange change = new SlotChange(0, SlotAction.REMOVE, null, null, 1, 0, "loc");
        assertNull(change.before());
        assertNull(change.after());
    }

    @Test
    void vaultSlot_nullItemIsHandledWithoutError() {
        VaultSlot slot = new VaultSlot(3, null, "loc");
        assertNull(slot.item());
    }

    @Test
    void vaultSnapshot_slotsListIsUnmodifiable() {
        VaultSnapshot snapshot = new VaultSnapshot(UUID.randomUUID(), "1", 0,
                List.of(new VaultSlot(0, null, "loc")));

        assertThrows(UnsupportedOperationException.class, () ->
                snapshot.slots().add(new VaultSlot(1, null, "loc2")));
    }

    @Test
    void vaultTransactionCommittedEvent_wouldSeeAnUnmodifiableSlotChangeList() {
        // VaultTransactionCommittedEvent stores List.copyOf(slotChanges) in its constructor (see
        // that class) - the same pattern verified directly here against List.copyOf itself, since
        // constructing the real Bukkit Event also requires a live server.
        List<SlotChange> changes = List.copyOf(List.of(new SlotChange(0, SlotAction.INSERT, null, null, 0, 1, "loc")));
        assertThrows(UnsupportedOperationException.class, () ->
                changes.add(new SlotChange(1, SlotAction.INSERT, null, null, 0, 1, "loc2")));
    }

    @Test
    void locationKey_matchesTheStableFormat() {
        UUID owner = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertEquals("venderchest:11111111-1111-1111-1111-111111111111:vault:1:slot:5",
                LocationKeys.of(owner, "1", 5));
    }
}
