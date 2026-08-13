package com.valerin.venderchest.crossserver;

import org.bukkit.event.inventory.InventoryAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Package-private, Bukkit-object-free transfer arithmetic used by the event adapter. */
final class InventoryTransferEngine<T> {

    private final StackOps<T> ops;
    private final int playerStorageSize;

    InventoryTransferEngine(StackOps<T> ops, int playerStorageSize) {
        this.ops = ops;
        this.playerStorageSize = playerStorageSize;
    }

    Optional<Result<T>> click(Click<T> input) {
        List<T> vaultBefore = copy(input.vault());
        List<T> vaultAfter = copy(input.vault());
        List<T> playerBefore = copy(input.player());
        List<T> playerReserved = copy(input.player());
        List<T> playerAfter = copy(input.player());
        T cursorBefore = ops.copy(input.cursor());
        T cursorReserved = ops.copy(input.cursor());
        T cursorAfter = ops.copy(input.cursor());

        if (input.action() == InventoryAction.COLLECT_TO_CURSOR) {
            if (ops.empty(cursorBefore)) return Optional.empty();
            int capacity = ops.max(cursorBefore) - ops.amount(cursorBefore);
            if (capacity <= 0) return Optional.empty();
            int collected = 0;
            for (int slot = 0; slot < vaultAfter.size() && collected < capacity; slot++) {
                T item = vaultAfter.get(slot);
                if (ops.empty(item) || !ops.similar(item, cursorBefore)) continue;
                int moved = Math.min(capacity - collected, ops.amount(item));
                vaultAfter.set(slot, subtract(item, moved));
                collected += moved;
            }
            for (int slot = 0; slot < playerAfter.size() && collected < capacity; slot++) {
                T item = playerAfter.get(slot);
                if (ops.empty(item) || !ops.similar(item, cursorBefore)) continue;
                int moved = Math.min(capacity - collected, ops.amount(item));
                T remaining = subtract(item, moved);
                playerReserved.set(slot, remaining);
                playerAfter.set(slot, remaining);
                collected += moved;
            }
            if (collected == 0) return Optional.empty();
            cursorAfter = ops.withAmount(cursorBefore, ops.amount(cursorBefore) + collected);
            return Optional.of(new Result<>(vaultBefore, vaultAfter, playerBefore, playerReserved,
                    playerAfter, cursorBefore, cursorReserved, cursorAfter));
        }

        if (input.clickedTop()) {
            if (input.slot() < 0 || input.slot() >= vaultBefore.size()) return Optional.empty();
            T top = vaultBefore.get(input.slot());
            switch (input.action()) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                    int amount = pickupAmount(input.action(), top, cursorBefore);
                    if (amount <= 0) return Optional.empty();
                    vaultAfter.set(input.slot(), subtract(top, amount));
                    cursorAfter = addSimilar(cursorBefore, top, amount);
                }
                case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                    int amount = placeAmount(input.action(), top, cursorBefore);
                    if (amount <= 0) return Optional.empty();
                    cursorAfter = subtract(cursorBefore, amount);
                    vaultAfter.set(input.slot(), addSimilar(top, cursorBefore, amount));
                }
                case SWAP_WITH_CURSOR -> {
                    if (ops.empty(cursorBefore) && ops.empty(top)) return Optional.empty();
                    cursorAfter = ops.copy(top);
                    vaultAfter.set(input.slot(), ops.copy(cursorBefore));
                }
                case MOVE_TO_OTHER_INVENTORY -> {
                    if (ops.empty(top)) return Optional.empty();
                    T remaining = distribute(top, playerAfter, playerStorageSize);
                    if (ops.amount(top) == ops.amount(remaining)) return Optional.empty();
                    vaultAfter.set(input.slot(), remaining);
                }
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                    int slot = input.hotbarSlot();
                    if (slot < 0 || slot >= playerBefore.size()) return Optional.empty();
                    T source = playerBefore.get(slot);
                    if (ops.empty(source) && ops.empty(top)) return Optional.empty();
                    playerReserved.set(slot, null);
                    playerAfter.set(slot, ops.copy(top));
                    vaultAfter.set(input.slot(), ops.copy(source));
                }
                default -> { return Optional.empty(); }
            }
        } else {
            if (input.slot() < 0 || input.slot() >= playerBefore.size()) return Optional.empty();
            T source = playerBefore.get(input.slot());
            switch (input.action()) {
                case MOVE_TO_OTHER_INVENTORY -> {
                    if (ops.empty(source)) return Optional.empty();
                    T remaining = distribute(source, vaultAfter, vaultAfter.size());
                    if (ops.amount(source) == ops.amount(remaining)) return Optional.empty();
                    playerReserved.set(input.slot(), remaining);
                    playerAfter.set(input.slot(), ops.copy(remaining));
                }
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                    int amount = pickupAmount(input.action(), source, cursorBefore);
                    if (amount <= 0) return Optional.empty();
                    playerReserved.set(input.slot(), subtract(source, amount));
                    playerAfter.set(input.slot(), subtract(source, amount));
                    cursorAfter = addSimilar(cursorBefore, source, amount);
                }
                case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                    int amount = placeAmount(input.action(), source, cursorBefore);
                    if (amount <= 0) return Optional.empty();
                    playerAfter.set(input.slot(), addSimilar(source, cursorBefore, amount));
                    cursorAfter = subtract(cursorBefore, amount);
                }
                case SWAP_WITH_CURSOR -> {
                    if (ops.empty(source) && ops.empty(cursorBefore)) return Optional.empty();
                    playerAfter.set(input.slot(), ops.copy(cursorBefore));
                    cursorAfter = ops.copy(source);
                }
                default -> { return Optional.empty(); }
            }
        }
        return Optional.of(new Result<>(vaultBefore, vaultAfter, playerBefore, playerReserved,
                playerAfter, cursorBefore, cursorReserved, cursorAfter));
    }

    Optional<Result<T>> drag(Drag<T> input) {
        List<T> vaultBefore = copy(input.vault());
        List<T> vaultAfter = copy(input.vault());
        List<T> playerBefore = copy(input.player());
        List<T> playerReserved = copy(input.player());
        List<T> playerAfter = copy(input.player());
        if (ops.empty(input.oldCursor())) return Optional.empty();
        for (Map.Entry<Integer, T> entry : input.topResults().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= vaultAfter.size()) return Optional.empty();
            T before = vaultBefore.get(slot);
            T after = entry.getValue();
            if (!ops.empty(after) && !ops.similar(after, input.oldCursor())) return Optional.empty();
            if (!ops.empty(before) && !ops.similar(before, input.oldCursor())) return Optional.empty();
            if (ops.amount(after) < ops.amount(before) || ops.amount(after) > ops.max(input.oldCursor())) {
                return Optional.empty();
            }
            vaultAfter.set(slot, ops.copy(after));
        }
        for (Map.Entry<Integer, T> entry : input.playerResults().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= playerAfter.size()) return Optional.empty();
            T before = playerBefore.get(slot);
            T after = entry.getValue();
            if (!ops.empty(after) && !ops.similar(after, input.oldCursor())) return Optional.empty();
            if (!ops.empty(before) && !ops.similar(before, input.oldCursor())) return Optional.empty();
            if (ops.amount(after) < ops.amount(before) || ops.amount(after) > ops.max(input.oldCursor())) {
                return Optional.empty();
            }
            playerAfter.set(slot, ops.copy(after));
        }
        return Optional.of(new Result<>(vaultBefore, vaultAfter, playerBefore, playerReserved,
                playerAfter, ops.copy(input.oldCursor()), ops.copy(input.oldCursor()),
                ops.copy(input.newCursor())));
    }

    private int pickupAmount(InventoryAction action, T top, T cursor) {
        if (ops.empty(top) || (!ops.empty(cursor) && !ops.similar(cursor, top))) return 0;
        int capacity = ops.max(top) - ops.amount(cursor);
        return switch (action) {
            case PICKUP_ALL, PICKUP_SOME -> Math.min(ops.amount(top), capacity);
            case PICKUP_HALF -> Math.min((ops.amount(top) + 1) / 2, capacity);
            case PICKUP_ONE -> Math.min(1, capacity);
            default -> 0;
        };
    }

    private int placeAmount(InventoryAction action, T target, T cursor) {
        if (ops.empty(cursor) || (!ops.empty(target) && !ops.similar(target, cursor))) return 0;
        int capacity = ops.max(cursor) - ops.amount(target);
        return switch (action) {
            case PLACE_ALL, PLACE_SOME -> Math.min(ops.amount(cursor), capacity);
            case PLACE_ONE -> Math.min(1, capacity);
            default -> 0;
        };
    }

    private T distribute(T source, List<T> target, int limit) {
        int remaining = ops.amount(source);
        int capped = Math.min(limit, target.size());
        for (int slot = 0; slot < capped && remaining > 0; slot++) {
            T current = target.get(slot);
            if (ops.empty(current) || !ops.similar(current, source)) continue;
            int moved = Math.min(remaining, ops.max(current) - ops.amount(current));
            if (moved > 0) {
                target.set(slot, ops.withAmount(current, ops.amount(current) + moved));
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < capped && remaining > 0; slot++) {
            if (!ops.empty(target.get(slot))) continue;
            int moved = Math.min(remaining, ops.max(source));
            target.set(slot, ops.withAmount(source, moved));
            remaining -= moved;
        }
        return remaining == 0 ? null : ops.withAmount(source, remaining);
    }

    private T subtract(T item, int amount) {
        int remaining = ops.amount(item) - amount;
        return remaining <= 0 ? null : ops.withAmount(item, remaining);
    }

    private T addSimilar(T current, T source, int amount) {
        return ops.withAmount(ops.empty(current) ? source : current, ops.amount(current) + amount);
    }

    private List<T> copy(List<T> items) {
        List<T> result = new ArrayList<>(items.size());
        for (T item : items) result.add(ops.copy(item));
        return result;
    }

    interface StackOps<T> {
        boolean empty(T stack);
        boolean similar(T left, T right);
        int amount(T stack);
        int max(T stack);
        T withAmount(T stack, int amount);
        T copy(T stack);
    }

    record Click<T>(List<T> vault, List<T> player, T cursor, boolean clickedTop,
                    int slot, InventoryAction action, int hotbarSlot) {}

    record Drag<T>(List<T> vault, List<T> player, T oldCursor, T newCursor,
                   Map<Integer, T> topResults, Map<Integer, T> playerResults) {
        Drag {
            topResults = Map.copyOf(new LinkedHashMap<>(topResults));
            playerResults = Map.copyOf(new LinkedHashMap<>(playerResults));
        }
    }

    record Result<T>(List<T> vaultBefore, List<T> vaultAfter,
                     List<T> playerBefore, List<T> playerReserved, List<T> playerAfter,
                     T cursorBefore, T cursorReserved, T cursorAfter) {}
}
