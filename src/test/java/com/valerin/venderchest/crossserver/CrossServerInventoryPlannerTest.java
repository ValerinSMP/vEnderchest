package com.valerin.venderchest.crossserver;

import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerInventoryPlannerTest {

    private final InventoryTransferEngine<Stack> engine = new InventoryTransferEngine<>(new Ops(), 36);

    @Test
    void cursorOriginTransfersUseDeterministicVaultAndCursorSnapshots() {
        for (InventoryAction action : List.of(
                InventoryAction.PLACE_ALL,
                InventoryAction.PLACE_ONE,
                InventoryAction.PLACE_SOME)) {
            var result = click(vault(), player(), stack("diamond", 5), true, 2, action, -1);
            assertTrue(result.cursorAfter() == null || result.cursorAfter().amount() < 5, action.name());
        }
        List<Stack> vault = vault();
        vault.set(2, stack("gold", 2));
        var swap = click(vault, player(), stack("diamond", 1), true, 2,
                InventoryAction.SWAP_WITH_CURSOR, -1);
        assertEquals(stack("gold", 2), swap.cursorAfter());
        assertEquals(stack("diamond", 1), swap.vaultAfter().get(2));
    }

    @Test
    void normalWithdrawalUsesCursorLikeVanillaWithoutTouchingPlayerSlots() {
        List<Stack> vault = vault();
        vault.set(3, stack("sword", 1));

        var result = click(vault, player(), null, true, 3, InventoryAction.PICKUP_ALL, -1);

        assertEquals(stack("sword", 1), result.cursorAfter());
        assertNull(result.playerAfter().get(0));
        assertNull(result.vaultAfter().get(3));
    }

    @Test
    void rightAndLeftPickupHonorCursorCapacity() {
        List<Stack> vault = vault();
        vault.set(0, stack("emerald", 12));
        var one = click(vault, player(), null, true, 0, InventoryAction.PICKUP_ONE, -1);
        assertEquals(stack("emerald", 1), one.cursorAfter());
        assertEquals(stack("emerald", 11), one.vaultAfter().get(0));

        var half = click(vault, player(), null, true, 0, InventoryAction.PICKUP_HALF, -1);
        assertEquals(stack("emerald", 6), half.cursorAfter());
    }

    @Test
    void cursorMergeUsesExactFingerprintAndHonorsCustomMaxStack() {
        List<Stack> vault = vault();
        vault.set(0, stack("custom:pdc-a", 10, 16));
        var result = click(vault, player(), stack("custom:pdc-a", 15, 16), true, 0,
                InventoryAction.PICKUP_ALL, -1);
        assertEquals(stack("custom:pdc-a", 16, 16), result.cursorAfter());
        assertEquals(stack("custom:pdc-a", 9, 16), result.vaultAfter().get(0));
    }

    @Test
    void shiftWithdrawalPlansExactPlayerSlotsAndRejectsFullInventory() {
        List<Stack> vault = vault();
        vault.set(0, stack("emerald", 12));
        List<Stack> player = player();
        player.set(5, stack("emerald", 60));

        var result = click(vault, player, null, true, 0,
                InventoryAction.MOVE_TO_OTHER_INVENTORY, -1);

        assertEquals(stack("emerald", 64), result.playerAfter().get(5));
        assertEquals(stack("emerald", 8), result.playerAfter().get(0));
        assertNull(result.vaultAfter().get(0));

        List<Stack> full = player();
        for (int slot = 0; slot < 36; slot++) full.set(slot, stack("stone", 64));
        assertTrue(engine.click(new InventoryTransferEngine.Click<>(vault, full, null, true,
                0, InventoryAction.MOVE_TO_OTHER_INVENTORY, -1)).isEmpty());
    }

    @Test
    void shiftInsertAndHotbarSwapReserveOnlyTheOwnedSource() {
        List<Stack> player = player();
        player.set(7, stack("gold", 3));
        var inserted = click(vault(), player, null, false, 7,
                InventoryAction.MOVE_TO_OTHER_INVENTORY, -1);
        assertEquals(stack("gold", 3), inserted.playerBefore().get(7));
        assertNull(inserted.playerReserved().get(7));
        assertNull(inserted.playerAfter().get(7));

        List<Stack> vault = vault();
        vault.set(1, stack("iron", 2));
        player.set(1, stack("diamond", 1));
        var swapped = click(vault, player, null, true, 1, InventoryAction.HOTBAR_SWAP, 1);
        assertNull(swapped.playerReserved().get(1));
        assertEquals(stack("iron", 2), swapped.playerAfter().get(1));
        assertEquals(stack("diamond", 1), swapped.vaultAfter().get(1));
    }

    @Test
    void shiftWithdrawalMovesWhatFitsAndLeavesDeterministicRemainder() {
        List<Stack> vault = vault();
        vault.set(0, stack("emerald", 12));
        List<Stack> player = player();
        for (int slot = 0; slot < 36; slot++) player.set(slot, stack("stone", 64));
        player.set(5, stack("emerald", 60));

        var result = click(vault, player, null, true, 0,
                InventoryAction.MOVE_TO_OTHER_INVENTORY, -1);
        assertEquals(stack("emerald", 64), result.playerAfter().get(5));
        assertEquals(stack("emerald", 8), result.vaultAfter().get(0));
    }

    @Test
    void collectToCursorPlansAllMatchingSlotsButDropCloneAndUnknownStayBlocked() {
        List<Stack> vault = vault();
        vault.set(0, stack("unique", 2));
        List<Stack> player = player();
        player.set(3, stack("unique", 4));
        var collected = click(vault, player, stack("unique", 1), true, 0,
                InventoryAction.COLLECT_TO_CURSOR, -1);
        assertEquals(stack("unique", 7), collected.cursorAfter());
        assertNull(collected.vaultAfter().get(0));
        assertNull(collected.playerAfter().get(3));
        for (InventoryAction action : List.of(
                InventoryAction.DROP_ALL_CURSOR,
                InventoryAction.DROP_ONE_CURSOR,
                InventoryAction.DROP_ALL_SLOT,
                InventoryAction.DROP_ONE_SLOT,
                InventoryAction.CLONE_STACK,
                InventoryAction.UNKNOWN,
                InventoryAction.NOTHING)) {
            assertTrue(engine.click(new InventoryTransferEngine.Click<>(
                    vault, player(), null, true, 0, action, -1)).isEmpty(), action.name());
        }
    }

    @Test
    void dragCanSplitAcrossVaultAndPlayerWhileConservingRemainder() {
        var result = engine.drag(new InventoryTransferEngine.Drag<>(vault(), player(),
                stack("redstone", 6), stack("redstone", 2),
                Map.of(2, stack("redstone", 2)), Map.of(7, stack("redstone", 2))))
                .orElseThrow();

        assertEquals(stack("redstone", 2), result.vaultAfter().get(2));
        assertEquals(stack("redstone", 2), result.playerAfter().get(7));
        assertEquals(stack("redstone", 2), result.cursorAfter());
    }


    private InventoryTransferEngine.Result<Stack> click(
            List<Stack> vault, List<Stack> player, Stack cursor, boolean top, int slot,
            InventoryAction action, int hotbar) {
        return engine.click(new InventoryTransferEngine.Click<>(
                vault, player, cursor, top, slot, action, hotbar)).orElseThrow();
    }

    private List<Stack> vault() { return new ArrayList<>(java.util.Collections.nCopies(45, null)); }
    private List<Stack> player() { return new ArrayList<>(java.util.Collections.nCopies(41, null)); }
    private Stack stack(String type, int amount) { return new Stack(type, amount, 64); }
    private Stack stack(String type, int amount, int max) { return new Stack(type, amount, max); }

    private record Stack(String type, int amount, int max) {}

    private static final class Ops implements InventoryTransferEngine.StackOps<Stack> {
        @Override public boolean empty(Stack stack) { return stack == null || stack.amount() <= 0; }
        @Override public boolean similar(Stack left, Stack right) { return left.type().equals(right.type()); }
        @Override public int amount(Stack stack) { return empty(stack) ? 0 : stack.amount(); }
        @Override public int max(Stack stack) { return stack.max(); }
        @Override public Stack withAmount(Stack stack, int amount) { return new Stack(stack.type(), amount, stack.max()); }
        @Override public Stack copy(Stack stack) { return stack; }
    }
}
