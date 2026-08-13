package com.valerin.venderchest.crossserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

public final class VaultPayloadCodec {

    private VaultPayloadCodec() {}

    public static String encode(ItemStack[] items) {
        JsonArray array = new JsonArray();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) array.add(JsonNull.INSTANCE);
            else array.add(Base64.getEncoder().encodeToString(item.clone().serializeAsBytes()));
        }
        return array.toString();
    }

    public static ItemStack[] decode(String payload) {
        JsonArray array = JsonParser.parseString(payload).getAsJsonArray();
        ItemStack[] items = new ItemStack[array.size()];
        for (int slot = 0; slot < array.size(); slot++) {
            JsonElement value = array.get(slot);
            if (!value.isJsonNull()) {
                items[slot] = ItemStack.deserializeBytes(Base64.getDecoder().decode(value.getAsString()));
            }
        }
        return items;
    }
}
