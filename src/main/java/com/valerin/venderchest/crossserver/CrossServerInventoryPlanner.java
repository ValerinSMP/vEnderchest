package com.valerin.venderchest.crossserver;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Main-thread adapter from cloned Bukkit stacks to the pure transfer engine. */
public final class CrossServerInventoryPlanner {

    public static final int PLAYER_STORAGE_SIZE = 36;

    private final InventoryTransferEngine<ItemStack> engine =
            new InventoryTransferEngine<>(new BukkitStackOps(), PLAYER_STORAGE_SIZE);

    public Optional<PlannedMutation> click(ClickInput input) {
        return engine.click(new InventoryTransferEngine.Click<>(Arrays.asList(input.vault()),
                Arrays.asList(input.player()), input.cursor(), input.clickedTop(), input.slot(),
                input.action(), input.hotbarSlot())).filter(result -> same(
                        result.cursorBefore(), result.cursorAfter())).flatMap(this::encode);
    }

    public Optional<CursorActionPlan> cursorClick(ClickInput input) {
        return engine.click(new InventoryTransferEngine.Click<>(Arrays.asList(input.vault()),
                        Arrays.asList(input.player()), input.cursor(), input.clickedTop(), input.slot(),
                        input.action(), input.hotbarSlot()))
                .filter(this::conservesItems)
                .map(this::encodeCursorAction);
    }

    public Optional<PlannedMutation> drag(DragInput input) {
        return engine.drag(new InventoryTransferEngine.Drag<>(Arrays.asList(input.vault()),
                Arrays.asList(input.player()), input.oldCursor(), input.newCursor(),
                input.topResults(), input.playerResults()))
                .flatMap(this::encode);
    }

    public Optional<CursorActionPlan> cursorDrag(DragInput input) {
        return engine.drag(new InventoryTransferEngine.Drag<>(Arrays.asList(input.vault()),
                        Arrays.asList(input.player()), input.oldCursor(), input.newCursor(),
                        input.topResults(), input.playerResults()))
                .filter(this::conservesItems)
                .map(this::encodeCursorAction);
    }

    private Optional<PlannedMutation> encode(InventoryTransferEngine.Result<ItemStack> result) {
        List<SlotMutation> slots = new ArrayList<>();
        for (int slot = 0; slot < result.playerBefore().size(); slot++) {
            ItemStack before = result.playerBefore().get(slot);
            ItemStack reserved = result.playerReserved().get(slot);
            ItemStack after = result.playerAfter().get(slot);
            if (!same(before, reserved) || !same(before, after)) {
                slots.add(new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, slot),
                        value(before), value(reserved), value(after)));
            }
        }
        ItemStack[] before = result.vaultBefore().toArray(ItemStack[]::new);
        ItemStack[] after = result.vaultAfter().toArray(ItemStack[]::new);
        String beforePayload = VaultPayloadCodec.encode(before);
        String afterPayload = VaultPayloadCodec.encode(after);
        if (beforePayload.equals(afterPayload)) return Optional.empty();
        return Optional.of(new PlannedMutation(new MutationPlan(slots), beforePayload, afterPayload));
    }

    private CursorActionPlan encodeCursorAction(InventoryTransferEngine.Result<ItemStack> result) {
        List<SlotMutation> slots = new ArrayList<>();
        for (int slot = 0; slot < result.playerBefore().size(); slot++) {
            ItemStack before = result.playerBefore().get(slot);
            ItemStack reserved = result.playerReserved().get(slot);
            ItemStack after = result.playerAfter().get(slot);
            if (!same(before, reserved) || !same(before, after)) {
                slots.add(new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, slot),
                        value(before), value(reserved), value(after)));
            }
        }
        return new CursorActionPlan(List.copyOf(slots), value(result.cursorBefore()),
                value(result.cursorAfter()), VaultPayloadCodec.encode(
                result.vaultBefore().toArray(ItemStack[]::new)), VaultPayloadCodec.encode(
                result.vaultAfter().toArray(ItemStack[]::new)));
    }

    private boolean conservesItems(InventoryTransferEngine.Result<ItemStack> result) {
        List<ItemStack> before = items(result.vaultBefore(), result.playerBefore(), result.cursorBefore());
        List<ItemStack> after = items(result.vaultAfter(), result.playerAfter(), result.cursorAfter());
        return sameMultiset(before, after);
    }

    private List<ItemStack> items(List<ItemStack> vault, List<ItemStack> player, ItemStack cursor) {
        List<ItemStack> result = new ArrayList<>(vault.size() + player.size() + 1);
        result.addAll(vault);
        result.addAll(player);
        result.add(cursor);
        return result;
    }

    static boolean sameMultiset(List<ItemStack> left, List<ItemStack> right) {
        return sameMultiset(left, right, CrossServerInventoryPlanner::empty,
                ItemStack::getAmount, ItemStack::isSimilar);
    }

    static <T> boolean sameMultiset(List<T> left, List<T> right, Predicate<T> empty,
                                    ToIntFunction<T> amount, BiPredicate<T, T> similar) {
        List<ItemAmount<T>> expected = group(left, empty, amount, similar);
        List<ItemAmount<T>> actual = group(right, empty, amount, similar);
        if (expected.size() != actual.size()) return false;
        for (ItemAmount<T> item : expected) {
            ItemAmount<T> match = actual.stream().filter(candidate ->
                    similar.test(item.identity(), candidate.identity())).findFirst().orElse(null);
            if (match == null || item.amount() != match.amount()) return false;
        }
        return true;
    }

    private static <T> List<ItemAmount<T>> group(List<T> items, Predicate<T> empty,
                                                 ToIntFunction<T> amount, BiPredicate<T, T> similar) {
        List<ItemAmount<T>> groups = new ArrayList<>();
        for (T item : items) {
            if (empty.test(item)) continue;
            int index = -1;
            for (int candidate = 0; candidate < groups.size(); candidate++) {
                if (similar.test(groups.get(candidate).identity(), item)) { index = candidate; break; }
            }
            if (index < 0) {
                groups.add(new ItemAmount<>(item, amount.applyAsInt(item)));
            } else {
                ItemAmount<T> current = groups.get(index);
                groups.set(index, new ItemAmount<>(current.identity(),
                        Math.addExact(current.amount(), amount.applyAsInt(item))));
            }
        }
        return groups;
    }

    private SlotValue value(ItemStack item) {
        return empty(item) ? SlotValue.empty() : SlotValue.fromBytes(item.clone().serializeAsBytes());
    }

    private static boolean same(ItemStack left, ItemStack right) {
        if (empty(left) || empty(right)) return empty(left) && empty(right);
        return left.equals(right);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private record ItemAmount<T>(T identity, int amount) {}

    private static ItemStack[] cloneArray(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int slot = 0; slot < items.length; slot++) copy[slot] = cloneItem(items[slot]);
        return copy;
    }

    private static ItemStack cloneItem(ItemStack item) { return empty(item) ? null : item.clone(); }

    public record ClickInput(ItemStack[] vault, ItemStack[] player, ItemStack cursor,
                             boolean clickedTop, int slot, InventoryAction action, int hotbarSlot) {
        public ClickInput {
            vault = cloneArray(vault);
            player = cloneArray(player);
            cursor = cloneItem(cursor);
        }
    }

    public record DragInput(ItemStack[] vault, ItemStack[] player, ItemStack oldCursor,
                            ItemStack newCursor, Map<Integer, ItemStack> topResults,
                            Map<Integer, ItemStack> playerResults) {
        public DragInput {
            vault = cloneArray(vault);
            player = cloneArray(player);
            oldCursor = cloneItem(oldCursor);
            newCursor = cloneItem(newCursor);
            Map<Integer, ItemStack> copied = new LinkedHashMap<>();
            topResults.forEach((slot, item) -> copied.put(slot, cloneItem(item)));
            topResults = Map.copyOf(copied);
            Map<Integer, ItemStack> copiedPlayer = new LinkedHashMap<>();
            playerResults.forEach((slot, item) -> copiedPlayer.put(slot, cloneItem(item)));
            playerResults = Map.copyOf(copiedPlayer);
        }
    }

    public record CursorActionPlan(
            List<SlotMutation> playerSlots,
            SlotValue cursorBefore,
            SlotValue cursorAfter,
            String vaultBefore,
            String vaultAfter
    ) {
        public CursorActionPlan {
            playerSlots = List.copyOf(playerSlots);
        }

        public boolean changesVault() { return !vaultBefore.equals(vaultAfter); }
        public boolean changesPlayer() { return !playerSlots.isEmpty(); }
    }

    private static final class BukkitStackOps implements InventoryTransferEngine.StackOps<ItemStack> {
        @Override public boolean empty(ItemStack stack) { return CrossServerInventoryPlanner.empty(stack); }
        @Override public boolean similar(ItemStack left, ItemStack right) { return left.isSimilar(right); }
        @Override public int amount(ItemStack stack) { return empty(stack) ? 0 : stack.getAmount(); }
        @Override public int max(ItemStack stack) { return stack.getMaxStackSize(); }
        @Override public ItemStack withAmount(ItemStack stack, int amount) {
            ItemStack copy = stack.clone();
            copy.setAmount(amount);
            return copy;
        }
        @Override public ItemStack copy(ItemStack stack) { return cloneItem(stack); }
    }
}
