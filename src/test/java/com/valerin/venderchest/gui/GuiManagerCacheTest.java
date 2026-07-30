package com.valerin.venderchest.gui;

import com.valerin.venderchest.storage.Storage;
import com.valerin.venderchest.session.VaultKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class GuiManagerCacheTest {

    @Test
    void reopeningUsesNewerRevisionWhenOlderLoadCompletesLate() {
        GuiManager manager = new GuiManager(null, null, null, null, null);
        VaultKey key = new VaultKey(java.util.UUID.randomUUID(), "2");
        Storage.PageRecord revision27 = new Storage.PageRecord(new ItemStack[45], 27);
        Storage.PageRecord revision28 = new Storage.PageRecord(new ItemStack[45], 28);

        manager.cacheLatest(key, revision28);
        manager.cacheLatest(key, revision27);

        assertSame(revision28, manager.cachedPage(key));
    }

    @Test
    void equalRevisionCannotReplaceAlreadyPublishedState() {
        Storage.PageRecord published = new Storage.PageRecord(new ItemStack[45], 28);
        Storage.PageRecord lateEquivalentLoad = new Storage.PageRecord(new ItemStack[45], 28);

        assertSame(published, GuiManager.preferNewestRevision(published, lateEquivalentLoad));
    }
}
