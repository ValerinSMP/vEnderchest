package com.valerin.venderchest.crossserver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CrossServerIsolationTest {

    @Test
    void asyncDomainStoresOnlyIdsFencesAndDefensivePlansNeverBukkitViewsOrEvents() {
        for (Class<?> type : List.of(
                CrossServerMutationController.class,
                PlannedMutation.class,
                MutationPlan.class,
                MutationJournalRecord.class,
                SlotMutation.class,
                SlotRef.class,
                SlotValue.class)) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getType().getName();
                assertFalse(name.startsWith("org.bukkit.entity.Player")
                                || name.startsWith("org.bukkit.inventory.Inventory")
                                || name.startsWith("org.bukkit.event."),
                        () -> type.getSimpleName() + "." + field.getName() + " retains " + name);
            }
        }
    }

    @Test
    void crossServerWriterHasNoCursorSlotOrCursorWriteCall() throws IOException {
        assertEquals(List.of(SlotRef.Area.PLAYER), List.of(SlotRef.Area.values()));
        try (var bytecode = BukkitPlayerDataPort.class.getResourceAsStream("BukkitPlayerDataPort.class")) {
            String constants = new String(bytecode.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("setItemOnCursor"));
        }
    }
}
