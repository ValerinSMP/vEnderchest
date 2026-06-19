package com.valerin.venderchest.gui;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.model.OpenSession;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class GuiManager {

    private final VEnderchest plugin;
    private final Storage storage;
    private final ConfigManager config;
    private final MainMenuGui mainMenuGui;
    private final EnderchestGui enderchestGui;

    /** UUID → current open session */
    private final Map<UUID, OpenSession> sessions = new ConcurrentHashMap<>();
    /** UUID → last opened page number (updated at request time, used as sequence guard) */
    private final Map<UUID, Integer> lastPage = new ConcurrentHashMap<>();
    /** UUID → pending open counter (prevents stale async callbacks from reopening old pages) */
    private final Map<UUID, AtomicInteger> openSeq = new ConcurrentHashMap<>();
    /**
     * "uuid:page" → pending content snapshot awaiting DB write.
     * Serves as source of truth between session close and async DB write completing,
     * preventing a dupe where a fast reopen loads the stale DB state.
     */
    private final Map<String, ItemStack[]> writeBuffer = new ConcurrentHashMap<>();

    public GuiManager(VEnderchest plugin, Storage storage, ConfigManager config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
        this.mainMenuGui = new MainMenuGui(config);
        this.enderchestGui = new EnderchestGui(config);
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    public void openMainMenu(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var itemCounts = storage.countPageItems(uuid);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                Inventory inv = mainMenuGui.build(player, itemCounts);
                OpenSession session = new OpenSession(-1, inv);
                sessions.put(uuid, session);
                player.openInventory(inv);
                config.playSound(player, "open-menu");
            });
        });
    }

    public void openPage(Player player, int page) {
        openPageInternal(player, page);
    }

    /**
     * Opens another player's vault for an admin.
     * @param readOnly true = solo lectura; false = editable
     */
    public void openPageAdmin(Player admin, UUID targetUuid, String targetName, int page, boolean readOnly) {
        int maxPages = config.getMaxPages();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemStack[] content = storage.loadPage(targetUuid, page);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!admin.isOnline()) return;
                String mode  = readOnly ? "<red>[VER]" : "<green>[EDIT]";
                String title = "<gray>" + targetName + " " + mode + " <dark_gray>Pg " + page + "/" + maxPages;
                Inventory inv = enderchestGui.build(content, page, maxPages, title);
                OpenSession session = new OpenSession(page, inv, readOnly, targetUuid, targetName);
                sessions.put(admin.getUniqueId(), session);
                admin.openInventory(inv);
            });
        });
    }

    public void openLastPageOrDefault(Player player) {
        int page = lastPage.getOrDefault(player.getUniqueId(), 1);
        int maxPages = config.getMaxPages(player);
        if (page > maxPages) page = 1;
        openPage(player, page);
    }

    private void openPageInternal(Player player, int page) {
        UUID uuid = player.getUniqueId();
        int maxPages = config.getMaxPages(player);
        // Track last requested page; async callback is discarded if a newer request arrives
        lastPage.put(uuid, page);
        int seq = openSeq.computeIfAbsent(uuid, k -> new AtomicInteger(0)).incrementAndGet();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String bufKey = uuid + ":" + page;
            ItemStack[] content = writeBuffer.containsKey(bufKey)
                    ? writeBuffer.get(bufKey)          // pending write is source of truth
                    : storage.loadPage(uuid, page);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                // Discard if the player already navigated elsewhere while we were loading
                if (openSeq.getOrDefault(uuid, new AtomicInteger(0)).get() != seq) return;
                Inventory inv = enderchestGui.build(content, page, maxPages);
                OpenSession session = new OpenSession(page, inv);
                sessions.put(uuid, session);
                player.openInventory(inv);
                config.playSound(player, "open-page");
            });
        });
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    public void navigatePage(Player player, int page) {
        UUID uuid = player.getUniqueId();
        OpenSession current = sessions.get(uuid);
        if (current != null) {
            flushAsync(uuid, current);
        }
        config.playSound(player, "navigate");
        if (current != null && current.isAdminView()) {
            openPageAdmin(player, current.getTargetUuid(), current.getTargetName(), page, current.isReadOnly());
        } else {
            openPage(player, page);
        }
    }

    public void navigateToMainMenu(Player player) {
        UUID uuid = player.getUniqueId();
        OpenSession current = sessions.get(uuid);
        if (current != null) {
            flushAsync(uuid, current);
        }
        config.playSound(player, "click-home");
        openMainMenu(player);
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    public void onClose(UUID uuid, Inventory closedInventory) {
        OpenSession session = sessions.get(uuid);
        if (session == null) return;
        // Guard: only act if the inventory being closed is the session's inventory.
        if (!session.getInventory().equals(closedInventory)) return;
        sessions.remove(uuid);
        if (session.getPage() < 1) return; // main menu, nothing to save
        flushAsync(uuid, session);
    }

    /** Called async by autosave task — already on a side thread, write directly. */
    public void saveAllDirty() {
        for (Map.Entry<UUID, OpenSession> entry : sessions.entrySet()) {
            OpenSession s = entry.getValue();
            if (s.getPage() < 1 || s.isReadOnly() || !s.isDirty()) continue;
            s.clearDirty();
            UUID target = s.isAdminView() ? s.getTargetUuid() : entry.getKey();
            storage.savePage(target, s.getPage(), EnderchestGui.extractContent(s.getInventory()));
        }
    }

    /** Schedules a DB write on a side thread; snapshot is buffered immediately as source of truth. */
    private void flushAsync(UUID adminOrOwner, OpenSession s) {
        if (s.getPage() < 1 || s.isReadOnly() || !s.isDirty()) return;
        UUID target = s.isAdminView() ? s.getTargetUuid() : adminOrOwner;
        ItemStack[] snapshot = EnderchestGui.extractContent(s.getInventory());
        String key = target + ":" + s.getPage();
        writeBuffer.put(key, snapshot); // fast reopen reads this before DB write completes
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            storage.savePage(target, s.getPage(), snapshot);
            writeBuffer.remove(key, snapshot); // conditional remove: only clears if no newer flush replaced it
        });
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public boolean isOurInventory(Inventory inv) {
        return sessions.values().stream().anyMatch(s -> s.getInventory().equals(inv));
    }

    public OpenSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public MainMenuGui getMainMenuGui() { return mainMenuGui; }

    /** Saves dirty pages and force-closes all open GUIs. Used on reload. */
    public void closeAll() {
        for (Map.Entry<UUID, OpenSession> entry : sessions.entrySet()) {
            OpenSession s = entry.getValue();
            // Reload is synchronous; flush inline (no async here so we don't return before saves finish)
            if (s.getPage() > 0 && !s.isReadOnly() && s.isDirty()) {
                UUID target = s.isAdminView() ? s.getTargetUuid() : entry.getKey();
                storage.savePage(target, s.getPage(), EnderchestGui.extractContent(s.getInventory()));
            }
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null) p.closeInventory();
        }
        sessions.clear();
    }

    public void cleanup(UUID uuid) {
        sessions.remove(uuid);
        lastPage.remove(uuid);
        openSeq.remove(uuid);
    }
}
