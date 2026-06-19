package com.valerin.venderchest.model;

import org.bukkit.inventory.Inventory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenSession {

    private final int page;
    private final Inventory inventory;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final boolean adminView;
    private final boolean readOnly;
    private final UUID targetUuid;   // non-null for admin sessions
    private final String targetName; // for GUI title

    /** Regular player session. */
    public OpenSession(int page, Inventory inventory) {
        this.page       = page;
        this.inventory  = inventory;
        this.adminView  = false;
        this.readOnly   = false;
        this.targetUuid = null;
        this.targetName = null;
    }

    /** Admin session — readOnly=true: solo ver; readOnly=false: editar. */
    public OpenSession(int page, Inventory inventory, boolean readOnly, UUID targetUuid, String targetName) {
        this.page       = page;
        this.inventory  = inventory;
        this.adminView  = true;
        this.readOnly   = readOnly;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    public int getPage()            { return page; }
    public Inventory getInventory() { return inventory; }
    public boolean isDirty()        { return dirty.get(); }
    public void markDirty()         { dirty.set(true); }
    public void clearDirty()        { dirty.set(false); }
    public boolean isAdminView()    { return adminView; }
    public boolean isReadOnly()     { return readOnly; }
    public UUID getTargetUuid()     { return targetUuid; }
    public String getTargetName()   { return targetName; }
}
