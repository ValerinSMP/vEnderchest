package com.valerin.venderchest.model;

import com.valerin.venderchest.session.VaultSession;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Bukkit-side pairing of a live {@link Inventory} with the authoritative {@link VaultSession} that
 * owns it (null for the main menu and for read-only admin views, neither of which can ever be
 * written back). {@code originalSnapshot} is the content this session's vault held the last time it
 * was loaded or committed — the baseline {@code VaultTransactionService} diffs the live inventory
 * against to compute what actually changed.
 */
public final class OpenSession {

    private final int page;
    private final Inventory inventory;
    private final boolean adminView;
    private final boolean readOnly;
    private final UUID targetUuid;
    private final String targetName;
    private final VaultSession vaultSession;
    private volatile ItemStack[] originalSnapshot;

    private OpenSession(int page, Inventory inventory, boolean adminView, boolean readOnly,
                         UUID targetUuid, String targetName, VaultSession vaultSession, ItemStack[] originalSnapshot) {
        this.page = page;
        this.inventory = inventory;
        this.adminView = adminView;
        this.readOnly = readOnly;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.vaultSession = vaultSession;
        this.originalSnapshot = originalSnapshot;
    }

    /** The main menu (page == -1). Never participates in the vault session registry. */
    public static OpenSession mainMenu(Inventory inventory) {
        return new OpenSession(-1, inventory, false, false, null, null, null, null);
    }

    /** A regular player's own vault page. */
    public static OpenSession playerPage(int page, Inventory inventory, VaultSession vaultSession, ItemStack[] originalSnapshot) {
        return new OpenSession(page, inventory, false, false, null, null, vaultSession, originalSnapshot);
    }

    /** An admin viewing/editing another player's vault page. {@code vaultSession} is null when {@code readOnly}. */
    public static OpenSession adminPage(int page, Inventory inventory, boolean readOnly, UUID targetUuid,
                                         String targetName, VaultSession vaultSession, ItemStack[] originalSnapshot) {
        return new OpenSession(page, inventory, true, readOnly, targetUuid, targetName, vaultSession, originalSnapshot);
    }

    public int getPage()             { return page; }
    public Inventory getInventory()  { return inventory; }
    public boolean isAdminView()     { return adminView; }
    public boolean isReadOnly()      { return readOnly; }
    public UUID getTargetUuid()      { return targetUuid; }
    public String getTargetName()    { return targetName; }

    /** Null for the main menu and for read-only admin views. */
    public VaultSession getVaultSession() { return vaultSession; }

    public ItemStack[] getOriginalSnapshot() { return originalSnapshot; }

    /** Called after a successful mid-session commit (e.g. autosave) so the next diff is incremental. */
    public void updateOriginalSnapshot(ItemStack[] newBaseline) {
        this.originalSnapshot = newBaseline;
    }
}
