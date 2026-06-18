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

public class GuiManager {

    private final VEnderchest plugin;
    private final Storage storage;
    private final ConfigManager config;
    private final MainMenuGui mainMenuGui;
    private final EnderchestGui enderchestGui;

    /** UUID → current open session */
    private final Map<UUID, OpenSession> sessions = new ConcurrentHashMap<>();
    /** UUID → last opened page number */
    private final Map<UUID, Integer> lastPage = new ConcurrentHashMap<>();

    public GuiManager(VEnderchest plugin, Storage storage, ConfigManager config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
        this.mainMenuGui = new MainMenuGui(config);
        this.enderchestGui = new EnderchestGui(config);
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    public void openMainMenu(Player player) {
        Inventory inv = mainMenuGui.build(player);
        OpenSession session = new OpenSession(-1, inv, false); // page -1 = main menu
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inv);
    }

    public void openPage(Player player, int page) {
        openPageInternal(player, page, false);
    }

    public void openPageAdmin(Player player, UUID target, int page) {
        ItemStack[] content = storage.loadPage(target, page);
        int maxPages = config.getMaxPages();
        Inventory inv = enderchestGui.build(content, page, maxPages);
        OpenSession session = new OpenSession(page, inv, true);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inv);
    }

    public void openLastPageOrDefault(Player player) {
        int page = lastPage.getOrDefault(player.getUniqueId(), 1);
        int maxPages = config.getMaxPages(player);
        if (page > maxPages) page = 1;
        openPage(player, page);
    }

    private void openPageInternal(Player player, int page, boolean admin) {
        ItemStack[] content = storage.loadPage(player.getUniqueId(), page);
        int maxPages = config.getMaxPages(player);
        Inventory inv = enderchestGui.build(content, page, maxPages);
        OpenSession session = new OpenSession(page, inv, admin);
        sessions.put(player.getUniqueId(), session);
        lastPage.put(player.getUniqueId(), page);
        player.openInventory(inv);
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    public void navigatePage(Player player, int page) {
        // Save current page first, then open new one
        UUID uuid = player.getUniqueId();
        OpenSession current = sessions.get(uuid);
        if (current != null && current.getPage() > 0 && current.isDirty()) {
            storage.savePage(uuid, current.getPage(), EnderchestGui.extractContent(current.getInventory()));
        }
        openPage(player, page);
    }

    public void navigateToMainMenu(Player player) {
        UUID uuid = player.getUniqueId();
        OpenSession current = sessions.get(uuid);
        if (current != null && current.getPage() > 0 && current.isDirty()) {
            storage.savePage(uuid, current.getPage(), EnderchestGui.extractContent(current.getInventory()));
        }
        openMainMenu(player);
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    public void onClose(UUID uuid, Inventory closedInventory) {
        OpenSession session = sessions.get(uuid);
        if (session == null) return;
        // When navigating between GUIs, the new session is already registered before
        // openInventory() fires the close event for the old inventory.
        // Guard: only act if the inventory being closed is the session's inventory.
        if (!session.getInventory().equals(closedInventory)) return;
        sessions.remove(uuid);
        if (session.getPage() < 1) return; // main menu, nothing to save
        if (session.isAdminView()) return;
        if (session.isDirty()) {
            storage.savePage(uuid, session.getPage(), EnderchestGui.extractContent(session.getInventory()));
        }
    }

    /** Called async by autosave task. */
    public void saveAllDirty() {
        for (Map.Entry<UUID, OpenSession> entry : sessions.entrySet()) {
            OpenSession s = entry.getValue();
            if (s.getPage() < 1 || s.isAdminView() || !s.isDirty()) continue;
            s.clearDirty();
            storage.savePage(entry.getKey(), s.getPage(), EnderchestGui.extractContent(s.getInventory()));
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public boolean isOurInventory(Inventory inv) {
        return sessions.values().stream().anyMatch(s -> s.getInventory().equals(inv));
    }

    public OpenSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public MainMenuGui getMainMenuGui() { return mainMenuGui; }

    public void cleanup(UUID uuid) {
        sessions.remove(uuid);
        lastPage.remove(uuid);
    }
}
