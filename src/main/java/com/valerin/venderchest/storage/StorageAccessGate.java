package com.valerin.venderchest.storage;

/** Atomic barrier between ordinary storage work and the one-way migration maintenance state. */
public final class StorageAccessGate {
    private boolean maintenance;
    private int activeOperations;

    public synchronized boolean tryBegin() {
        if (maintenance) return false;
        activeOperations++;
        return true;
    }

    public synchronized void end() {
        if (activeOperations <= 0) throw new IllegalStateException("storage operation underflow");
        activeOperations--;
    }

    public synchronized boolean enterMaintenanceIfIdle() {
        if (maintenance || activeOperations != 0) return false;
        maintenance = true;
        return true;
    }

    public synchronized boolean isMaintenance() { return maintenance; }
    public synchronized int activeOperations() { return activeOperations; }
}
