package com.valerin.venderchest.gui;

import com.valerin.venderchest.storage.Storage;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.UUID;

/**
 * Per-admin ephemeral state while browsing/restoring backups through the GUI. Deliberately never
 * touches {@link com.valerin.venderchest.session.VaultSessionRegistry} - these are either pure
 * read-only views or a single atomic restore write, so vault-session exclusivity doesn't apply.
 */
final class BackupBrowseState {

    final UUID targetUuid;
    final String targetName;
    final List<Storage.BackupRecord> backups;

    int listPage;
    Inventory inventory;
    Integer previewingBackupId;
    int previewingPage;
    ItemStack[] previewingItems;
    boolean confirmPending;
    BukkitTask confirmRevertTask;

    BackupBrowseState(UUID targetUuid, String targetName, List<Storage.BackupRecord> backups) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backups = backups;
    }
}
