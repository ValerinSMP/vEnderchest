package com.valerin.venderchest.model;

import org.bukkit.inventory.Inventory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenSession {

    private final int page;
    private final Inventory inventory;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final boolean adminView; // true = solo lectura (ecadmin view)

    public OpenSession(int page, Inventory inventory, boolean adminView) {
        this.page = page;
        this.inventory = inventory;
        this.adminView = adminView;
    }

    public int getPage() { return page; }
    public Inventory getInventory() { return inventory; }
    public boolean isDirty() { return dirty.get(); }
    public void markDirty() { dirty.set(true); }
    public void clearDirty() { dirty.set(false); }
    public boolean isAdminView() { return adminView; }
}
