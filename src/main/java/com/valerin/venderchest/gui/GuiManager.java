package com.valerin.venderchest.gui;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.model.OpenSession;
import com.valerin.venderchest.session.CommitOutcome;
import com.valerin.venderchest.session.BukkitItemSnapshot;
import com.valerin.venderchest.session.ItemBalanceDelta;
import com.valerin.venderchest.session.ItemBalanceDeltaEngine;
import com.valerin.venderchest.session.ItemSnapshot;
import com.valerin.venderchest.session.OpenAttempt;
import com.valerin.venderchest.session.SessionState;
import com.valerin.venderchest.session.VaultKey;
import com.valerin.venderchest.session.VaultSession;
import com.valerin.venderchest.session.VaultSessionRegistry;
import com.valerin.venderchest.session.VaultTransactionService;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Server-authoritative vault GUI orchestrator. Every open/close/navigate/autosave/quit/shutdown
 * path funnels through {@link VaultSessionRegistry} (which guarantees at most one live session per
 * vault) and {@link VaultTransactionService} (which persists via optimistic-concurrency CAS) —
 * nothing here trusts the client to send a close packet or to open pages in any particular order.
 *
 * <p>{@code openByPlayer} tracks what each actor's client currently has open; it is a fast path
 * only. The registry, keyed by vault rather than by actor, is what actually prevents two sessions
 * from ever coexisting against the same vault — see {@link #resolveSupersede} for the case where
 * the two maps transiently disagree (e.g. two opens issued in the same tick, before the first has
 * even finished loading).
 */
public class GuiManager {

    private final VEnderchest plugin;
    private final Storage storage;
    private final ConfigManager config;
    private final MainMenuGui mainMenuGui;
    private final EnderchestGui enderchestGui;
    private final BackupListGui backupListGui;
    private final BackupPreviewGui backupPreviewGui;
    private final VaultSessionRegistry registry;
    private final VaultTransactionService txService;
    private final BooleanSupplier primaryThread;
    private final Consumer<Runnable> mainThreadScheduler;

    /** Actor UUID -> what that actor's client currently has open. Main-thread only. */
    private final Map<UUID, OpenSession> openByPlayer = new ConcurrentHashMap<>();
    /** Actor UUID -> last opened page number. */
    private final Map<UUID, Integer> lastPage = new ConcurrentHashMap<>();
    /** Actor UUID -> newest requested GUI. A late async completion may never replace it. */
    private final Map<UUID, Long> openRequests = new ConcurrentHashMap<>();
    /**
     * Last known content+revision per vault, kept only while the owner is online (evicted on
     * disconnect, same lifetime AxVaults gives its in-memory {@code Vault} objects). A reopen that
     * hits this cache skips the DB round-trip entirely instead of re-reading unchanged data - the
     * CAS on write is what already guarantees correctness, this is purely a read-side optimization.
     * Kept up to date by {@link #applyCommitOutcome}; evicted on a conflict (our copy is stale) or
     * an out-of-band write such as {@code /ecadmin clear} (see {@link #invalidateCache}).
     */
    private final Map<VaultKey, Storage.PageRecord> contentCache = new ConcurrentHashMap<>();
    /** Admin UUID -> ephemeral backup browse/preview/restore state. Never touches vault sessions. */
    private final Map<UUID, BackupBrowseState> backupBrowse = new ConcurrentHashMap<>();

    public GuiManager(VEnderchest plugin, Storage storage, ConfigManager config,
                       VaultSessionRegistry registry, VaultTransactionService txService) {
        this(plugin, storage, config, registry, txService, Bukkit::isPrimaryThread,
                task -> plugin.getServer().getScheduler().runTask(plugin, task));
    }

    GuiManager(VEnderchest plugin, Storage storage, ConfigManager config,
               VaultSessionRegistry registry, VaultTransactionService txService,
               BooleanSupplier primaryThread, Consumer<Runnable> mainThreadScheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
        this.registry = registry;
        this.txService = txService;
        this.primaryThread = primaryThread;
        this.mainThreadScheduler = mainThreadScheduler;
        this.mainMenuGui = new MainMenuGui(config);
        this.enderchestGui = new EnderchestGui(config);
        this.backupListGui = new BackupListGui(config);
        this.backupPreviewGui = new BackupPreviewGui(config);
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    public void openMainMenu(Player player) {
        runOnMainThread(() -> openMainMenuOnMain(player));
    }

    private void openMainMenuOnMain(Player player) {
        UUID uuid = player.getUniqueId();
        long request = beginOpenRequest(uuid);
        closeCurrentVaultThenRun(player, false, request, () -> {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                var itemCounts = storage.countPageItems(uuid);
                runOnMainThread(() -> {
                    if (!isCurrentOpenRequest(uuid, request) || !player.isOnline()) return;
                    Inventory inv = mainMenuGui.build(player, itemCounts);
                    publishOpenSession(player, OpenSession.mainMenu(inv));
                    config.playSound(player, "open-menu");
                });
            });
        });
    }

    public void openPage(Player player, int page) {
        runOnMainThread(() -> openPageOnMain(player, page));
    }

    private void openPageOnMain(Player player, int page) {
        UUID actorUuid = player.getUniqueId();
        long request = beginOpenRequest(actorUuid);
        closeCurrentVaultThenRun(player, true, request,
                () -> beginOpenAndLoad(player, actorUuid, null, page, false, request));
    }

    /**
     * Opens another player's vault for an admin.
     * @param readOnly true = solo lectura; false = editable
     */
    public void openPageAdmin(Player admin, UUID targetUuid, String targetName, int page, boolean readOnly) {
        runOnMainThread(() -> openPageAdminOnMain(admin, targetUuid, targetName, page, readOnly));
    }

    private void openPageAdminOnMain(Player admin, UUID targetUuid, String targetName, int page, boolean readOnly) {
        long request = beginOpenRequest(admin.getUniqueId());
        if (readOnly) {
            openPageAdminReadOnly(admin, targetUuid, targetName, page, request);
            return;
        }
        closeCurrentVaultThenRun(admin, true, request,
                () -> beginOpenAndLoad(admin, targetUuid, targetName, page, true, request));
    }

    public void openLastPageOrDefault(Player player) {
        runOnMainThread(() -> openLastPageOrDefaultOnMain(player));
    }

    private void openLastPageOrDefaultOnMain(Player player) {
        int page = lastPage.getOrDefault(player.getUniqueId(), 1);
        int maxPages = config.getMaxPages(player);
        if (page > maxPages) page = 1;
        openPageOnMain(player, page);
    }

    /**
     * Read-only admin views never mutate content, so they don't participate in the exclusivity
     * registry at all: {@link com.valerin.venderchest.listener.GuiListener} unconditionally blocks
     * every write to a read-only session's inventory, so there is nothing for them to duplicate.
     */
    private void openPageAdminReadOnly(Player admin, UUID targetUuid, String targetName, int page, long request) {
        UUID actorUuid = admin.getUniqueId();
        closeCurrentVaultThenRun(admin, false, request, () -> {
            int maxPages = config.getMaxPages();
            VaultKey key = new VaultKey(targetUuid, String.valueOf(page));
            Storage.PageRecord cached = cachedPage(key);
            if (cached != null) {
                runOnMainThread(() -> {
                    if (isCurrentOpenRequest(actorUuid, request)) {
                        openReadOnlyAdminView(admin, targetUuid, targetName, page, maxPages, cached);
                    }
                });
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Storage.PageRecord record = storage.loadPageWithRevision(targetUuid, page);
                runOnMainThread(() -> {
                    if (!isCurrentOpenRequest(actorUuid, request)) return;
                    Storage.PageRecord latest = cacheLatest(key, record);
                    openReadOnlyAdminView(admin, targetUuid, targetName, page, maxPages, latest);
                });
            });
        });
    }

    private void openReadOnlyAdminView(Player admin, UUID targetUuid, String targetName, int page,
                                        int maxPages, Storage.PageRecord record) {
        if (!admin.isOnline()) return;
        String title = adminTitle(targetName, true, page, maxPages);
        Inventory inv = enderchestGui.build(record.items(), page, maxPages, title);
        OpenSession session = OpenSession.adminPage(page, inv, true, targetUuid, targetName, null, null);
        publishOpenSession(admin, session);
    }

    /** Reserves the vault key and, once granted, loads and opens it. Handles all three {@link OpenAttempt} outcomes. */
    private void beginOpenAndLoad(Player actor, UUID ownerUuid, String ownerName, int page,
                                  boolean adminView, long request) {
        UUID actorUuid = actor.getUniqueId();
        if (!isCurrentOpenRequest(actorUuid, request)) return;
        String vaultId = String.valueOf(page);
        OpenAttempt attempt = registry.beginOpen(ownerUuid, actorUuid, vaultId);
        if (attempt instanceof OpenAttempt.Created created) {
            proceedToLoad(actor, created.session(), ownerName, page, adminView, request);
        } else if (attempt instanceof OpenAttempt.Supersede supersede) {
            resolveSupersede(actor, supersede.previous(), request,
                    () -> beginOpenAndLoad(actor, ownerUuid, ownerName, page, adminView, request));
        } else if (attempt instanceof OpenAttempt.Rejected rejected) {
            txService.fireConcurrentSessionConflict(ownerUuid, actorUuid, vaultId, rejected.existing());
            actor.sendMessage(config.msg("vault-busy"));
            config.playSound(actor, "denied");
        }
    }

    /**
     * A session already exists for the requested key. Because {@code openByPlayer} only reflects
     * *activated* (fully loaded) sessions, this is reachable even when the caller already tried to
     * self-close first — e.g. two opens issued for the same actor in the same tick, before the
     * first one's async load has completed. This is the authoritative fallback that makes the fix
     * correct regardless of timing, not just a defensive extra.
     */
    private void resolveSupersede(Player actor, VaultSession prev, long request, Runnable retry) {
        if (!isCurrentOpenRequest(actor.getUniqueId(), request)) return;
        switch (prev.getState()) {
            case OPENING -> {
                // Nothing loaded yet, nothing to commit - free the key; the in-flight load's
                // eventual activate() call will fail and be discarded (see proceedToLoad).
                registry.close(prev.getSessionId());
                runIfCurrentOpenRequest(actor.getUniqueId(), request, retry);
            }
            case ACTIVE -> {
                OpenSession bukkitSide = openByPlayer.get(actor.getUniqueId());
                if (bukkitSide == null || bukkitSide.getVaultSession() != prev) {
                    // Bookkeeping desync (shouldn't normally happen) - force close defensively
                    // rather than guess at content that might not belong to this actor's client.
                    registry.close(prev.getSessionId());
                    txService.fireClosed(prev, CloseReason.CONFLICT);
                    runIfCurrentOpenRequest(actor.getUniqueId(), request, retry);
                    return;
                }
                txService.fireSuspiciousReopen(prev);
                ItemStack[] currentContent = EnderchestGui.extractContent(bukkitSide.getInventory());
                txService.commitIfActive(prev, bukkitSide.getOriginalSnapshot(), currentContent, outcome -> {
                    applyCommitOutcome(prev, outcome, currentContent);
                    if (outcome == CommitOutcome.NOT_OWNED) {
                        actor.sendMessage(config.msg("vault-busy"));
                        return;
                    }
                    if (outcome == CommitOutcome.CONFLICT) {
                        boolean rolledBack = rollbackRejectedTransfer(
                                actor, bukkitSide.getOriginalSnapshot(), currentContent);
                        actor.sendMessage(config.msg("vault-conflict-reverted"));
                        if (!rolledBack) {
                            finishReopen(actor, bukkitSide, prev);
                            return;
                        }
                    }
                    finishReopen(actor, bukkitSide, prev);
                    runIfCurrentOpenRequest(actor.getUniqueId(), request, retry);
                });
            }
            default -> {
                if (prev.getState() == SessionState.COMMITTING) {
                    txService.afterCurrentCommit(prev,
                            () -> runIfCurrentOpenRequest(actor.getUniqueId(), request, retry));
                }
            }
        }
    }

    void proceedToLoad(Player actor, VaultSession session, String ownerName, int page,
                       boolean adminView, long request) {
        if (!isCurrentOpenRequest(actor.getUniqueId(), request)) {
            registry.close(session.getSessionId());
            return;
        }
        UUID ownerUuid = session.getOwnerUuid();
        VaultKey key = new VaultKey(ownerUuid, session.getVaultId());
        Storage.PageRecord cached = cachedPage(key);
        if (cached != null) {
            applyLoadedRecordOnMain(actor, session, ownerName, page, adminView, cached, request);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Storage.PageRecord record = storage.loadPageWithRevision(ownerUuid, page);
            applyLoadedRecordOnMain(actor, session, ownerName, page, adminView, record, request);
        });
    }

    void applyLoadedRecordOnMain(Player actor, VaultSession session, String ownerName, int page,
                                 boolean adminView, Storage.PageRecord record, long request) {
        runOnMainThread(() -> {
            if (!isCurrentOpenRequest(actor.getUniqueId(), request)) {
                registry.close(session.getSessionId());
                return;
            }
            applyLoadedRecord(actor, session, ownerName, page, adminView,
                    cacheLatest(new VaultKey(session.getOwnerUuid(), session.getVaultId()), record));
        });
    }

    void applyLoadedRecord(Player actor, VaultSession session, String ownerName, int page,
                           boolean adminView, Storage.PageRecord record) {
        if (!actor.isOnline()) {
            registry.close(session.getSessionId());
            return;
        }
        int maxPages = adminView ? config.getMaxPages() : config.getMaxPages(actor);
        String title = adminView ? adminTitle(ownerName, false, page, maxPages) : null;
        Inventory inv = enderchestGui.build(record.items(), page, maxPages, title);

        if (!registry.activate(session.getSessionId(), record.revision(), inv)) {
            // Superseded/closed while this load was in flight - discard silently. The registry
            // protects one vault key; the actor request guard above also orders different keys.
            return;
        }

        ItemStack[] originalSnapshot = cloneArray(record.items());
        OpenSession bukkitSession = adminView
                ? OpenSession.adminPage(page, inv, false, session.getOwnerUuid(), ownerName, session, originalSnapshot)
                : OpenSession.playerPage(page, inv, session, originalSnapshot);
        publishOpenSession(actor, bukkitSession);
        lastPage.put(actor.getUniqueId(), page);
        txService.fireOpened(session);
        if (!adminView) config.playSound(actor, "open-page");
    }

    // ── Backup browsing (list -> preview -> confirm-guarded restore) ───────────
    //
    // Deliberately a separate subsystem from the vault session machinery above: these GUIs are
    // either pure read-only views or a single atomic restore write (which itself just goes through
    // Storage#savePage, the same CAS-with-retry every other write in the plugin uses), so none of
    // them need VaultSessionRegistry involvement. GuiListener checks backupBrowse state before
    // falling through to the vault-session click handling, so this never interacts with it.

    /** Is there a live (owner, page) vault session right now - used to refuse a restore into an open vault. */
    public boolean isVaultOpen(UUID owner, int page) {
        return registry.current(new VaultKey(owner, String.valueOf(page))).isPresent();
    }

    /** Opens the paginated, clickable list of {@code targetUuid}'s stored backups. */
    public void openBackupList(Player admin, UUID targetUuid, String targetName) {
        runOnMainThread(() -> openBackupListOnMain(admin, targetUuid, targetName));
    }

    private void openBackupListOnMain(Player admin, UUID targetUuid, String targetName) {
        UUID actorUuid = admin.getUniqueId();
        long request = beginOpenRequest(actorUuid);
        closeCurrentVaultThenRun(admin, false, request, () -> {
            clearBackupBrowse(actorUuid);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                List<Storage.BackupRecord> backups = storage.listBackups(targetUuid);
                runOnMainThread(() -> {
                    if (!isCurrentOpenRequest(actorUuid, request) || !admin.isOnline()) return;
                    BackupBrowseState state = new BackupBrowseState(targetUuid, targetName, backups);
                    backupBrowse.put(actorUuid, state);
                    showBackupList(admin, state);
                });
            });
        });
    }

    /** Opens a specific backup's preview directly by id (e.g. `/ecadmin restore <player> <id>`). */
    public void openBackupPreviewDirect(Player admin, UUID targetUuid, String targetName, int backupId) {
        runOnMainThread(() -> openBackupPreviewDirectOnMain(admin, targetUuid, targetName, backupId));
    }

    private void openBackupPreviewDirectOnMain(Player admin, UUID targetUuid, String targetName, int backupId) {
        UUID actorUuid = admin.getUniqueId();
        long request = beginOpenRequest(actorUuid);
        closeCurrentVaultThenRun(admin, false, request, () -> {
            clearBackupBrowse(actorUuid);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Storage.BackupRecord record = storage.getBackup(backupId);
                ItemStack[] items = (record != null && record.uuid().equals(targetUuid))
                        ? storage.loadBackupItems(backupId) : null;
                List<Storage.BackupRecord> backups = storage.listBackups(targetUuid);
                runOnMainThread(() -> {
                    if (!isCurrentOpenRequest(actorUuid, request) || !admin.isOnline()) return;
                    if (record == null || !record.uuid().equals(targetUuid) || items == null) {
                        admin.sendMessage(config.getMM().deserialize(
                                "<red>Ese backup no existe o no pertenece a " + targetName + "."));
                        return;
                    }
                    BackupBrowseState state = new BackupBrowseState(targetUuid, targetName, backups);
                    int index = backups.indexOf(record);
                    state.listPage = index >= 0 ? index / BackupListGui.PAGE_SIZE : 0;
                    state.previewingBackupId = backupId;
                    state.previewingPage = record.page();
                    state.previewingItems = items;
                    backupBrowse.put(actorUuid, state);
                    showBackupPreview(admin, state);
                });
            });
        });
    }

    /** True while {@code inv} is this admin's currently tracked backup-browse inventory. */
    public boolean isBackupBrowseInventory(Player admin, Inventory inv) {
        BackupBrowseState state = backupBrowse.get(admin.getUniqueId());
        return state != null && inv.equals(state.inventory);
    }

    /** Routes a click inside a backup-browse inventory to the list or preview handler as appropriate. */
    public void handleBackupBrowseClick(Player admin, int rawSlot) {
        BackupBrowseState state = backupBrowse.get(admin.getUniqueId());
        if (state == null) return;
        if (state.previewingBackupId == null) {
            handleBackupListClick(admin, state, rawSlot);
        } else {
            handleBackupPreviewClick(admin, state, rawSlot);
        }
    }

    public void handleBackupBrowseClose(UUID uuid, Inventory closedInventory) {
        BackupBrowseState state = backupBrowse.get(uuid);
        if (state == null || !closedInventory.equals(state.inventory)) return;
        clearBackupBrowse(uuid);
    }

    private void showBackupList(Player admin, BackupBrowseState state) {
        state.previewingBackupId = null;
        Inventory inv = backupListGui.build(state.targetName, state.backups, state.listPage);
        state.inventory = inv;
        admin.openInventory(inv);
    }

    private void handleBackupListClick(Player admin, BackupBrowseState state, int rawSlot) {
        if (rawSlot == BackupListGui.CLOSE_SLOT) {
            admin.closeInventory();
            return;
        }
        int totalPages = backupListGui.totalPages(state.backups);
        if (rawSlot == BackupListGui.PREV_SLOT && state.listPage > 0) {
            state.listPage--;
            showBackupList(admin, state);
            return;
        }
        if (rawSlot == BackupListGui.NEXT_SLOT && state.listPage < totalPages - 1) {
            state.listPage++;
            showBackupList(admin, state);
            return;
        }
        int backupId = backupListGui.backupIdForSlot(state.backups, state.listPage, rawSlot);
        if (backupId < 0) return;
        openBackupPreviewFromList(admin, state, backupId);
    }

    private void openBackupPreviewFromList(Player admin, BackupBrowseState state, int backupId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Storage.BackupRecord record = storage.getBackup(backupId);
            ItemStack[] items = record != null ? storage.loadBackupItems(backupId) : null;
            runOnMainThread(() -> {
                // The admin may have navigated away (or logged off) while this load was in flight.
                if (!admin.isOnline() || backupBrowse.get(admin.getUniqueId()) != state) return;
                if (record == null || items == null) {
                    admin.sendMessage(config.getMM().deserialize("<red>No se pudo leer ese backup."));
                    return;
                }
                state.previewingBackupId = backupId;
                state.previewingPage = record.page();
                state.previewingItems = items;
                state.confirmPending = false;
                cancelPendingRevert(state);
                showBackupPreview(admin, state);
            });
        });
    }

    private void showBackupPreview(Player admin, BackupBrowseState state) {
        Inventory inv = backupPreviewGui.build(state.targetName, state.previewingBackupId,
                state.previewingPage, state.previewingItems, state.confirmPending);
        state.inventory = inv;
        admin.openInventory(inv);
    }

    private void handleBackupPreviewClick(Player admin, BackupBrowseState state, int rawSlot) {
        if (rawSlot == BackupPreviewGui.CLOSE_SLOT) {
            admin.closeInventory();
            return;
        }
        if (rawSlot == BackupPreviewGui.BACK_SLOT) {
            cancelPendingRevert(state);
            state.confirmPending = false;
            showBackupList(admin, state);
            return;
        }
        if (rawSlot != BackupPreviewGui.RESTORE_SLOT) return;

        if (state.confirmPending) {
            performRestore(admin, state);
            return;
        }
        state.confirmPending = true;
        showBackupPreview(admin, state);
        cancelPendingRevert(state);
        state.confirmRevertTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            state.confirmRevertTask = null;
            if (backupBrowse.get(admin.getUniqueId()) != state || !state.confirmPending) return;
            state.confirmPending = false;
            if (admin.isOnline() && admin.getOpenInventory().getTopInventory().equals(state.inventory)) {
                showBackupPreview(admin, state);
            }
        }, 20L * 8); // 8 seconds to confirm before the button reverts
    }

    private void performRestore(Player admin, BackupBrowseState state) {
        int backupId = state.previewingBackupId;
        int page = state.previewingPage;
        UUID targetUuid = state.targetUuid;
        String targetName = state.targetName;
        ItemStack[] items = state.previewingItems;

        if (isVaultOpen(targetUuid, page)) {
            admin.sendMessage(config.getMM().deserialize("<red>La página " + page + " de " + targetName
                    + " está abierta ahora mismo. Pedile que la cierre antes de restaurar."));
            return;
        }

        cancelPendingRevert(state);
        admin.closeInventory();
        clearBackupBrowse(admin.getUniqueId());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            storage.savePage(targetUuid, page, items);
            runOnMainThread(() -> {
                invalidateCache(targetUuid, page);
                plugin.getLogger().warning("[vEnderchest] [audit] event=restore backup=" + backupId
                        + " owner=" + targetUuid + " vault=" + page + " by=" + admin.getName());
                admin.sendMessage(config.getMM().deserialize("<green>Backup <white>#" + backupId
                        + "</white> restaurado en la página <white>" + page + "</white> de <white>" + targetName + "</white>."));
            });
        });
    }

    private void clearBackupBrowse(UUID uuid) {
        BackupBrowseState state = backupBrowse.remove(uuid);
        if (state != null) cancelPendingRevert(state);
    }

    private void cancelPendingRevert(BackupBrowseState state) {
        if (state.confirmRevertTask != null) {
            state.confirmRevertTask.cancel();
            state.confirmRevertTask = null;
        }
    }

    private String adminTitle(String targetName, boolean readOnly, int page, int maxPages) {
        String mode = readOnly ? "<red>[VER]" : "<green>[EDIT]";
        return "<gray>" + targetName + " " + mode + " <dark_gray>Pg " + page + "/" + maxPages;
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    public void navigatePage(Player player, int page) {
        runOnMainThread(() -> navigatePageOnMain(player, page));
    }

    private void navigatePageOnMain(Player player, int page) {
        config.playSound(player, "navigate");
        OpenSession current = openByPlayer.get(player.getUniqueId());
        boolean wasAdmin = current != null && current.isAdminView();
        UUID targetUuid = current != null ? current.getTargetUuid() : null;
        String targetName = current != null ? current.getTargetName() : null;
        boolean readOnly = current != null && current.isReadOnly();
        if (wasAdmin) {
            openPageAdminOnMain(player, targetUuid, targetName, page, readOnly);
        } else {
            openPageOnMain(player, page);
        }
    }

    public void navigateToMainMenu(Player player) {
        config.playSound(player, "click-home");
        openMainMenu(player);
    }

    /**
     * Commits and closes whatever writable vault session this actor's client currently has open
     * (if any), then runs {@code continuation}. This is the fast path that makes most reopens never
     * even reach {@link #resolveSupersede}: it is driven entirely by server-side bookkeeping, not by
     * whether the client sent a close packet.
     */
    private void closeCurrentVaultThenRun(Player actor, boolean suspiciousReopen, long request,
                                          Runnable continuation) {
        UUID actorUuid = actor.getUniqueId();
        if (!isCurrentOpenRequest(actorUuid, request)) return;
        OpenSession current = openByPlayer.get(actorUuid);
        if (current == null) {
            runIfCurrentOpenRequest(actorUuid, request, continuation);
            return;
        }
        if (current.getVaultSession() == null) {
            actor.closeInventory();
            openByPlayer.remove(actorUuid, current);
            runIfCurrentOpenRequest(actorUuid, request, continuation);
            return;
        }
        VaultSession vs = current.getVaultSession();
        if (vs.getState() == SessionState.COMMITTING) {
            txService.afterCurrentCommit(vs,
                    () -> closeCurrentVaultThenRun(actor, suspiciousReopen, request, continuation));
            return;
        }
        if (vs.getState() == SessionState.CLOSED) {
            actor.closeInventory();
            openByPlayer.remove(actorUuid, current);
            runIfCurrentOpenRequest(actorUuid, request, continuation);
            return;
        }
        if (vs.getState() != SessionState.ACTIVE) {
            actor.sendMessage(config.msg("vault-busy"));
            config.playSound(actor, "denied");
            return;
        }
        if (suspiciousReopen) {
            txService.fireSuspiciousReopen(vs);
        }
        ItemStack[] currentContent = EnderchestGui.extractContent(current.getInventory());
        txService.commitIfActive(vs, current.getOriginalSnapshot(), currentContent, outcome -> {
            applyCommitOutcome(vs, outcome, currentContent);
            if (outcome == CommitOutcome.NOT_OWNED) {
                actor.sendMessage(config.msg("vault-busy"));
                return;
            }
            if (outcome == CommitOutcome.CONFLICT) {
                boolean rolledBack = rollbackRejectedTransfer(
                        actor, current.getOriginalSnapshot(), currentContent);
                actor.sendMessage(config.msg("vault-conflict-reverted"));
                if (!rolledBack) {
                    finishReopen(actor, current, vs);
                    return;
                }
            }
            finishReopen(actor, current, vs);
            runIfCurrentOpenRequest(actorUuid, request, continuation);
        });
    }

    /**
     * Invalidates the server-side session and closes the old client view before removing its
     * bookkeeping. Keeping the mapping until closeInventory() returns ensures stale packets sent
     * during the gap between commit completion and the next async load are still rejected.
     */
    private void finishReopen(Player actor, OpenSession current, VaultSession session) {
        registry.close(session.getSessionId());
        txService.fireClosed(session, CloseReason.REOPEN);
        actor.closeInventory();
        openByPlayer.remove(actor.getUniqueId(), current);
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Called by {@link com.valerin.venderchest.listener.GuiListener} on {@code InventoryCloseEvent}.
     * If the client never sends a close packet at all, this simply never fires — correctness does
     * not depend on it; every other trigger (reopen, navigate, quit, autosave, shutdown) commits
     * independently via server-side state.
     */
    public void onClose(UUID uuid, Inventory closedInventory) {
        OpenSession session = openByPlayer.get(uuid);
        if (session == null || !session.getInventory().equals(closedInventory)) return;
        if (session.getVaultSession() == null) {
            openByPlayer.remove(uuid, session);
            return; // main menu / read-only admin view
        }
        VaultSession vs = session.getVaultSession();
        if (vs.getState() == SessionState.COMMITTING) {
            txService.afterCurrentCommit(vs, () -> onClose(uuid, closedInventory));
            return;
        }
        if (vs.getState() == SessionState.CLOSED) {
            openByPlayer.remove(uuid, session);
            return;
        }
        ItemStack[] currentContent = EnderchestGui.extractContent(session.getInventory());
        txService.commitIfActive(vs, session.getOriginalSnapshot(), currentContent, outcome -> {
            applyCommitOutcome(vs, outcome, currentContent);
            if (outcome != CommitOutcome.NOT_OWNED) {
                if (outcome == CommitOutcome.CONFLICT) {
                    Player player = plugin.getServer().getPlayer(uuid);
                    if (player != null) {
                        rollbackRejectedTransfer(player, session.getOriginalSnapshot(), currentContent);
                        player.sendMessage(config.msg("vault-conflict-reverted"));
                    }
                }
                openByPlayer.remove(uuid, session);
                registry.close(vs.getSessionId());
                txService.fireClosed(vs, CloseReason.CLIENT_CLOSE);
            }
        });
    }

    /**
     * Autosave tick. Must be invoked on the main thread (it reads live {@code Inventory} contents);
     * each session's actual DB write is still dispatched asynchronously by
     * {@link VaultTransactionService#commitIfActive}.
     */
    public void saveAllDirty() {
        for (Map.Entry<UUID, OpenSession> entry : Map.copyOf(openByPlayer).entrySet()) {
            UUID actorUuid = entry.getKey();
            OpenSession s = entry.getValue();
            VaultSession vs = s.getVaultSession();
            if (vs == null || vs.getState() != SessionState.ACTIVE) continue;
            ItemStack[] currentContent = EnderchestGui.extractContent(s.getInventory());
            txService.commitIfActive(vs, s.getOriginalSnapshot(), currentContent, outcome -> {
                applyCommitOutcome(vs, outcome, currentContent);
                if (outcome == CommitOutcome.NOT_OWNED) return;
                if (outcome == CommitOutcome.COMMITTED || outcome == CommitOutcome.NO_CHANGE) {
                    s.updateOriginalSnapshot(currentContent);
                    return;
                }
                // CONFLICT: another writer moved the revision past what this session knew about -
                // discard the local session and kick rather than keep editing against stale data.
                Player actor = plugin.getServer().getPlayer(actorUuid);
                if (actor != null) {
                    rollbackRejectedTransfer(actor, s.getOriginalSnapshot(), currentContent);
                    actor.sendMessage(config.msg("vault-conflict-reverted"));
                }
                registry.close(vs.getSessionId());
                txService.fireClosed(vs, CloseReason.CONFLICT);
                if (actor != null) actor.closeInventory();
                openByPlayer.remove(actorUuid, s);
            });
        }
    }

    /**
     * Force-commits and closes any session this actor's client still has open, then clears
     * bookkeeping. Called on quit/kick. Idempotent with {@link #onClose}/{@link #resolveSupersede}
     * regardless of which one fires first — each session's commit can only be performed once
     * ({@link VaultSessionRegistry#beginCommit} is compare-and-swapped), so redundant calls for the
     * same session are safe no-ops.
     */
    public void handleDisconnect(UUID uuid, CloseReason reason) {
        invalidateOpenRequests(uuid);
        // The owner is going offline - their cached content is only ever valid while they're
        // online (same lifetime AxVaults gives its in-memory Vault objects), so drop it now
        // rather than risk it going stale relative to a future direct DB write.
        contentCache.keySet().removeIf(key -> key.ownerUuid().equals(uuid));
        clearBackupBrowse(uuid); // safe no-op if this uuid wasn't browsing backups
        OpenSession s = openByPlayer.get(uuid);
        lastPage.remove(uuid);
        if (s == null) return;
        VaultSession vs = s.getVaultSession();
        if (vs == null) {
            openByPlayer.remove(uuid, s);
            return;
        }
        if (vs.getState() == SessionState.COMMITTING) {
            txService.afterCurrentCommit(vs, () -> handleDisconnect(uuid, reason));
            return;
        }
        if (vs.getState() == SessionState.CLOSED) {
            openByPlayer.remove(uuid, s);
            return;
        }
        ItemStack[] currentContent = EnderchestGui.extractContent(s.getInventory());
        txService.commitIfActive(vs, s.getOriginalSnapshot(), currentContent, outcome -> {
            // Owner is offline either way; no point caching what we just committed for them.
            if (outcome != CommitOutcome.NOT_OWNED) {
                openByPlayer.remove(uuid, s);
                registry.close(vs.getSessionId());
                txService.fireClosed(vs, reason);
            }
        });
    }

    /**
     * Backstop for any tracked session whose actor is no longer online — defensive only; the
     * explicit quit/kick handling above should already have covered this in normal operation.
     * Bounded by the number of currently open sessions (at most the online player count), never a
     * database scan.
     */
    public void sweepOrphans() {
        for (UUID actorUuid : List.copyOf(openByPlayer.keySet())) {
            if (plugin.getServer().getPlayer(actorUuid) == null) {
                handleDisconnect(actorUuid, CloseReason.LOGOUT);
            }
        }
    }

    /**
     * Saves dirty pages synchronously and force-closes all open GUIs. Used by {@code /ecadmin
     * reload} and plugin shutdown — the one deliberate exception to "no SQL on the main thread"
     * (Bukkit does not reliably run scheduled async tasks to completion during disable/reload), see
     * {@link VaultTransactionService#commitSynchronously}.
     */
    public void closeAll(CloseReason reason) {
        for (UUID actorUuid : List.copyOf(openRequests.keySet())) invalidateOpenRequests(actorUuid);
        for (Map.Entry<UUID, OpenSession> entry : Map.copyOf(openByPlayer).entrySet()) {
            OpenSession s = entry.getValue();
            VaultSession vs = s.getVaultSession();
            if (vs != null) {
                ItemStack[] currentContent = EnderchestGui.extractContent(s.getInventory());
                txService.commitSynchronously(vs, s.getOriginalSnapshot(), currentContent);
                registry.close(vs.getSessionId());
                txService.fireClosed(vs, reason);
            }
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null) p.closeInventory();
        }
        openByPlayer.clear();
        contentCache.clear();

        for (Map.Entry<UUID, BackupBrowseState> entry : Map.copyOf(backupBrowse).entrySet()) {
            cancelPendingRevert(entry.getValue());
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null) p.closeInventory();
        }
        backupBrowse.clear();
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public boolean isOurInventory(Inventory inv) {
        return openByPlayer.values().stream().anyMatch(s -> s.getInventory().equals(inv));
    }

    public OpenSession getSession(UUID uuid) {
        return openByPlayer.get(uuid);
    }

    public MainMenuGui getMainMenuGui() { return mainMenuGui; }

    /**
     * Defense-in-depth check used by {@link com.valerin.venderchest.listener.GuiListener}: is this
     * {@code OpenSession}'s vault session still the one the registry considers current for its key?
     * In this design it always should be (an {@code Inventory} is only ever built for and shown by
     * the session that currently owns its key) — this exists as an explicit, cheap second check
     * rather than relying solely on inventory-object identity.
     */
    public boolean validateSessionOrReject(OpenSession session) {
        VaultSession vs = session.getVaultSession();
        if (vs == null) return true;
        boolean current = registry.isActive(vs.getSessionId(), session.getInventory());
        if (!current) {
            txService.fireEventFromClosedSession(vs.getOwnerUuid(), vs.getActorUuid(), vs.getVaultId(), vs.getSessionId());
        }
        return current;
    }

    /**
     * Keeps {@link #contentCache} consistent with what a commit attempt actually did. Only
     * {@code COMMITTED}/{@code NO_CHANGE} content is trustworthy enough to cache — on
     * {@code CONFLICT} the DB has already moved past what this session knew, so the safe move is to
     * drop any cached copy rather than serve a snapshot we know is stale. {@code NOT_OWNED} is a
     * no-op: whichever caller actually owns the in-flight commit will update the cache itself.
     */
    private void applyCommitOutcome(VaultSession session, CommitOutcome outcome, ItemStack[] content) {
        VaultKey key = new VaultKey(session.getOwnerUuid(), session.getVaultId());
        switch (outcome) {
            case COMMITTED, NO_CHANGE ->
                    cacheLatest(key, new Storage.PageRecord(cloneArray(content), session.getCurrentRevision()));
            case CONFLICT -> contentCache.remove(key);
            case NOT_OWNED -> { /* the owning caller will update the cache when its own commit settles */ }
        }
    }

    Storage.PageRecord cacheLatest(VaultKey key, Storage.PageRecord candidate) {
        return contentCache.compute(key, (ignored, current) -> preferNewestRevision(current, candidate));
    }

    Storage.PageRecord cachedPage(VaultKey key) {
        return contentCache.get(key);
    }

    static Storage.PageRecord preferNewestRevision(Storage.PageRecord current, Storage.PageRecord candidate) {
        return current == null || candidate.revision() > current.revision() ? candidate : current;
    }

    /**
     * Drops any cached content for {@code (owner, page)}. Call this after any write that bypasses
     * the normal session/commit path - currently only {@code /ecadmin clear}, which deletes the row
     * directly via {@link Storage#clearPage}.
     */
    public void invalidateCache(UUID owner, int page) {
        contentCache.remove(new VaultKey(owner, String.valueOf(page)));
    }

    /**
     * A rejected CAS means the database still owns the original vault content. Undo only the net
     * transfer between vault and player; moves between vault slots cancel out to zero.
     */
    private boolean rollbackRejectedTransfer(Player player, ItemStack[] before, ItemStack[] after) {
        List<ItemBalanceDelta> deltas = ItemBalanceDeltaEngine.between(toSnapshots(before), toSnapshots(after));
        boolean complete = true;
        for (ItemBalanceDelta delta : deltas) {
            ItemStack source = delta.gainedByVault() ? after[delta.sourceSlot()] : before[delta.sourceSlot()];
            if (delta.gainedByVault()) {
                returnToPlayer(player, source, delta.amount());
            } else if (!removeFromPlayer(player, source, delta.amount())) {
                complete = false;
            }
        }
        player.updateInventory();
        if (!complete) {
            plugin.getLogger().severe("[vEnderchest] [audit] event=conflict_rollback_incomplete actor="
                    + player.getUniqueId());
        }
        return complete;
    }

    private void returnToPlayer(Player player, ItemStack source, int amount) {
        int remaining = amount;
        int maxStack = Math.max(1, source.getMaxStackSize());
        while (remaining > 0) {
            ItemStack returned = source.clone();
            returned.setAmount(Math.min(remaining, maxStack));
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(returned);
            leftovers.values().forEach(item ->
                    player.getWorld().dropItemNaturally(player.getLocation(), item));
            remaining -= returned.getAmount();
        }
    }

    private boolean removeFromPlayer(Player player, ItemStack source, int amount) {
        int remaining = removeFromCursor(player, source, amount);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack item = storage[slot];
            if (item == null || !item.isSimilar(source)) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            player.getInventory().setItem(slot, item.getAmount() == 0 ? null : item);
            remaining -= removed;
        }
        if (remaining > 0) {
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (offhand.isSimilar(source)) {
                int removed = Math.min(remaining, offhand.getAmount());
                offhand.setAmount(offhand.getAmount() - removed);
                player.getInventory().setItemInOffHand(offhand.getAmount() == 0 ? null : offhand);
                remaining -= removed;
            }
        }
        return remaining == 0;
    }

    private int removeFromCursor(Player player, ItemStack source, int amount) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || !cursor.isSimilar(source)) {
            return amount;
        }
        int removed = Math.min(amount, cursor.getAmount());
        cursor.setAmount(cursor.getAmount() - removed);
        player.setItemOnCursor(cursor.getAmount() == 0 ? null : cursor);
        return amount - removed;
    }

    void runOnMainThread(Runnable task) {
        if (primaryThread.getAsBoolean()) {
            task.run();
        } else {
            mainThreadScheduler.accept(task);
        }
    }

    /** InventoryClickEvent forbids changing the open view until the next server tick. */
    public void runNextTick(Runnable task) {
        mainThreadScheduler.accept(task);
    }

    void publishOpenSession(Player actor, OpenSession session) {
        // openInventory synchronously closes the previous view. Keep its mapping visible until
        // that close event has finished so it can still snapshot and commit the old inventory.
        actor.openInventory(session.getInventory());
        openByPlayer.put(actor.getUniqueId(), session);
    }

    long beginOpenRequest(UUID actorUuid) {
        return openRequests.merge(actorUuid, 1L, Long::sum);
    }

    private void invalidateOpenRequests(UUID actorUuid) {
        beginOpenRequest(actorUuid);
    }

    private boolean isCurrentOpenRequest(UUID actorUuid, long request) {
        return openRequests.getOrDefault(actorUuid, 0L) == request;
    }

    private void runIfCurrentOpenRequest(UUID actorUuid, long request, Runnable task) {
        if (isCurrentOpenRequest(actorUuid, request)) task.run();
    }

    private static ItemSnapshot[] toSnapshots(ItemStack[] items) {
        ItemSnapshot[] snapshots = new ItemSnapshot[items.length];
        for (int i = 0; i < items.length; i++) {
            snapshots[i] = BukkitItemSnapshot.of(items[i]);
        }
        return snapshots;
    }

    private static ItemStack[] cloneArray(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) out[i] = items[i] == null ? null : items[i].clone();
        return out;
    }
}
