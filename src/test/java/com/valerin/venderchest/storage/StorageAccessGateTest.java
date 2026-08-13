package com.valerin.venderchest.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageAccessGateTest {

    @Test
    void maintenanceCannotRaceAnExistingOrNewStorageOperation() {
        StorageAccessGate gate = new StorageAccessGate();
        assertTrue(gate.tryBegin());
        assertEquals(1, gate.activeOperations());
        assertFalse(gate.enterMaintenanceIfIdle());

        gate.end();
        assertTrue(gate.enterMaintenanceIfIdle());
        assertTrue(gate.isMaintenance());
        assertFalse(gate.tryBegin());
        assertFalse(gate.enterMaintenanceIfIdle());
    }
}
