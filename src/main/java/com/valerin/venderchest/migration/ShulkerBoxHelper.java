package com.valerin.venderchest.migration;

import com.valerin.venderchest.storage.Storage;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class ShulkerBoxHelper {

    private static final int SHULKER_CAPACITY = 27;
    private static final int PAGE_SIZE        = 45;

    private ShulkerBoxHelper() {}

    /** Pack up to 27 items into one PURPLE_SHULKER_BOX. Returns null if items is empty. */
    public static ItemStack pack(List<ItemStack> items) {
        List<ItemStack> nonEmpty = items.stream()
                .filter(i -> i != null && !i.getType().isAir()).toList();
        if (nonEmpty.isEmpty()) return null;

        ItemStack shulker = new ItemStack(Material.PURPLE_SHULKER_BOX);
        if (shulker.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            for (int i = 0; i < Math.min(nonEmpty.size(), SHULKER_CAPACITY); i++) {
                box.getInventory().setItem(i, nonEmpty.get(i));
            }
            bsm.setBlockState(box);
            shulker.setItemMeta(bsm);
        }
        return shulker;
    }

    /** Splits items into 27-item shulker boxes. */
    public static List<ItemStack> packAll(List<ItemStack> items) {
        List<ItemStack> nonEmpty = items.stream()
                .filter(i -> i != null && !i.getType().isAir())
                .collect(java.util.stream.Collectors.toList());

        List<ItemStack> boxes = new ArrayList<>();
        for (int i = 0; i < nonEmpty.size(); i += SHULKER_CAPACITY) {
            ItemStack box = pack(nonEmpty.subList(i, Math.min(i + SHULKER_CAPACITY, nonEmpty.size())));
            if (box != null) boxes.add(box);
        }
        return boxes;
    }

    /**
     * Places each item into the first available free slot across pages 1..maxPages.
     * @return number of items that couldn't be placed (pages full)
     */
    public static int placeItems(UUID uuid, List<ItemStack> items, Storage storage, int maxPages, Logger log) {
        List<ItemStack> remaining = new ArrayList<>(items);

        for (int page = 1; page <= maxPages && !remaining.isEmpty(); page++) {
            ItemStack[] pageItems = storage.loadPage(uuid, page);
            boolean modified = false;

            for (int slot = 0; slot < PAGE_SIZE && !remaining.isEmpty(); slot++) {
                if (pageItems[slot] == null || pageItems[slot].getType().isAir()) {
                    pageItems[slot] = remaining.remove(0);
                    modified = true;
                }
            }

            if (modified) storage.savePage(uuid, page, pageItems);
        }

        return remaining.size();
    }
}
