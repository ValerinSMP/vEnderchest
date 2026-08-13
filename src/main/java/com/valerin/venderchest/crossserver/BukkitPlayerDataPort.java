package com.valerin.venderchest.crossserver;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BukkitPlayerDataPort implements CrossServerRecoveryService.PlayerDataPort {

    private final EscrowProjection projections;

    public BukkitPlayerDataPort(Plugin plugin) {
        projections = new EscrowProjection(plugin);
    }

    @Override
    public boolean isOnline(UUID actorUuid) {
        requireMainThread();
        Player player = Bukkit.getPlayer(actorUuid);
        return player != null && player.isOnline();
    }

    @Override
    public Map<SlotRef, SlotValue> snapshot(UUID actorUuid, MutationPlan plan) {
        requireMainThread();
        Player player = requirePlayer(actorUuid);
        Map<SlotRef, SlotValue> observed = new LinkedHashMap<>();
        for (SlotMutation mutation : plan.playerSlots()) {
            observed.put(mutation.slot(), value(read(player, mutation.slot())));
        }
        return Map.copyOf(observed);
    }

    @Override
    public boolean compareAndApply(
            UUID actorUuid,
            MutationPlan plan,
            Map<SlotRef, SlotValue> expected,
            MutationPlan.Phase target
    ) {
        requireMainThread();
        Player player = requirePlayer(actorUuid);
        for (SlotMutation mutation : plan.playerSlots()) {
            if (!value(read(player, mutation.slot())).equals(expected.get(mutation.slot()))) return false;
        }
        for (SlotMutation mutation : plan.playerSlots()) {
            SlotValue value = switch (target) {
                case BEFORE -> mutation.before();
                case RESERVED -> mutation.reserved();
                case AFTER -> mutation.after();
            };
            write(player, mutation.slot(), item(value));
        }
        return true;
    }

    @Override
    public void saveData(UUID actorUuid) {
        requireMainThread();
        requirePlayer(actorUuid).saveData();
    }

    @Override
    public MutationPlan planEscrowSettlement(UUID actorUuid, CursorEscrow escrow) {
        requireMainThread();
        Player player = requirePlayer(actorUuid);
        List<EscrowProjection.TaggedOccurrence> tagged = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            var tag = projections.tag(item);
            if (tag.isEmpty()) continue;
            tagged.add(new EscrowProjection.TaggedOccurrence(slot, tag.get(), value(item),
                    projections.canonicalValue(item)));
        }
        var escapedSlot = projections.exactEscapedSlot(escrow, tagged);

        List<SlotMutation> mutations = new ArrayList<>();
        if (escapedSlot.isPresent()) {
            int slot = escapedSlot.get();
            SlotValue before = value(player.getInventory().getItem(slot));
            mutations.add(new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, slot),
                    before, before, escrow.canonical()));
        } else {
            ItemStack canonical = projections.canonicalItem(escrow);
            int remaining = canonical.getAmount();
            for (int slot = 0; slot < CrossServerInventoryPlanner.PLAYER_STORAGE_SIZE && remaining > 0; slot++) {
                ItemStack current = player.getInventory().getItem(slot);
                if (current == null || current.getType().isAir() || !current.isSimilar(canonical)) continue;
                int moved = Math.min(remaining, current.getMaxStackSize() - current.getAmount());
                if (moved <= 0) continue;
                ItemStack after = current.clone();
                after.setAmount(current.getAmount() + moved);
                SlotValue before = value(current);
                mutations.add(new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, slot),
                        before, before, value(after)));
                remaining -= moved;
            }
            for (int slot = 0; slot < CrossServerInventoryPlanner.PLAYER_STORAGE_SIZE && remaining > 0; slot++) {
                ItemStack current = player.getInventory().getItem(slot);
                if (current != null && !current.getType().isAir()) continue;
                int moved = Math.min(remaining, canonical.getMaxStackSize());
                ItemStack after = canonical.clone();
                after.setAmount(moved);
                mutations.add(new SlotMutation(new SlotRef(SlotRef.Area.PLAYER, slot),
                        SlotValue.empty(), SlotValue.empty(), value(after)));
                remaining -= moved;
            }
            if (remaining > 0) return null;
        }
        long next = Math.addExact(escrow.opSequence(), 1);
        CursorSettlement settlement = new CursorSettlement(
                escapedSlot.isEmpty() ? CursorSettlement.Kind.FALLBACK : CursorSettlement.Kind.ESCAPED_TAG,
                CursorSettlement.Stage.PLANNED, next, escrow.projection(), SlotValue.empty(), null);
        return MutationPlan.settlement(mutations, escrow, settlement);
    }

    private ItemStack read(Player player, SlotRef slot) {
        if (slot.slot() >= player.getInventory().getSize()) {
            throw new IllegalArgumentException("player slot out of range");
        }
        return player.getInventory().getItem(slot.slot());
    }

    private void write(Player player, SlotRef slot, ItemStack item) {
        if (slot.slot() >= player.getInventory().getSize()) {
            throw new IllegalArgumentException("player slot out of range");
        }
        player.getInventory().setItem(slot.slot(), item);
    }

    private SlotValue value(ItemStack item) {
        return item == null || item.getType().isAir() ? SlotValue.empty() : SlotValue.fromBytes(item.serializeAsBytes());
    }

    private ItemStack item(SlotValue value) {
        byte[] bytes = value.bytes();
        if (bytes.length == 0) return null;
        SlotValue verified = SlotValue.fromBytes(bytes);
        if (!verified.equals(value)) throw new IllegalArgumentException("slot fingerprint mismatch");
        return ItemStack.deserializeBytes(bytes);
    }

    private Player requirePlayer(UUID actorUuid) {
        Player player = Bukkit.getPlayer(actorUuid);
        if (player == null || !player.isOnline()) throw new IllegalStateException("player disconnected");
        return player;
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("playerdata recovery must run on main thread");
    }
}
