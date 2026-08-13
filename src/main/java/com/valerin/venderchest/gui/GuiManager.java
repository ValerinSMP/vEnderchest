package com.valerin.venderchest.gui;

import com.valerin.venderchest.VEnderchest;
import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.config.ConfigManager;
import com.valerin.venderchest.crossserver.BukkitPlayerDataPort;
import com.valerin.venderchest.crossserver.CrossServerInventoryPlanner;
import com.valerin.venderchest.crossserver.CrossServerMutationController;
import com.valerin.venderchest.crossserver.CursorEscrow;
import com.valerin.venderchest.crossserver.EscrowProjection;
import com.valerin.venderchest.crossserver.MutationPlan;
import com.valerin.venderchest.crossserver.PlannedMutation;
import com.valerin.venderchest.crossserver.SlotRef;
import com.valerin.venderchest.crossserver.SlotValue;
import com.valerin.venderchest.crossserver.VaultPayloadCodec;
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
import com.valerin.venderchest.storage.StorageAccessGate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
public class GuiManager implements CrossServerMutationController.ViewPort {

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
    private final CrossServerInventoryPlanner crossServerPlanner = new CrossServerInventoryPlanner();
    private final BukkitPlayerDataPort playerData;
    private final EscrowProjection escrowProjection;
    private volatile CrossServerMutationController crossServer;
    private volatile boolean crossServerRequired;
    private volatile StorageAccessGate storageGate = new StorageAccessGate();

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
    private final Set<UUID> dirtySessions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyCaptureScheduled = ConcurrentHashMap.newKeySet();
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
        this.escrowProjection = new EscrowProjection(plugin);
        this.playerData = new BukkitPlayerDataPort(plugin);
    }

    public void setCrossServerController(CrossServerMutationController controller, boolean required) {
        if (crossServer != null) throw new IllegalStateException("cross-server controller already configured");
        crossServer = controller;
        crossServerRequired = required;
    }

    public void setCrossServerRequired(boolean required) {
        crossServerRequired = required;
    }

    public void setStorageAccessGate(StorageAccessGate storageGate) {
        this.storageGate = storageGate;
    }

    public boolean isStorageMaintenance() { return storageGate.isMaintenance(); }

    public boolean enterStorageMaintenance() { return storageGate.enterMaintenanceIfIdle(); }

    public boolean hasAnyOpenOrTrackedSession() {
        return !openByPlayer.isEmpty() || !registry.allTracked().isEmpty() || !backupBrowse.isEmpty();
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    public void openMainMenu(Player player) {
        runOnMainThread(() -> openMainMenuOnMain(player));
    }

    private void openMainMenuOnMain(Player player) {
        if (rejectMaintenance(player)) return;
        UUID uuid = player.getUniqueId();
        long request = beginOpenRequest(uuid);
        closeCurrentVaultThenRun(player, false, request, () -> {
            runStorageAsync(() -> {
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
        if (rejectMaintenance(player)) return;
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
        if (rejectMaintenance(admin)) return;
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
            runStorageAsync(() -> {
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
        if (!crossServerRequired) {
            handleOpenAttempt(actor, ownerUuid, ownerName, page, adminView, request,
                    registry.beginOpen(ownerUuid, actorUuid, vaultId), null);
            return;
        }
        if (hasLegacyEscrowTag(actor)) {
            actor.sendMessage(config.msg("cross-server-quarantined"));
            config.playSound(actor, "denied");
            return;
        }
        CrossServerMutationController controller = crossServer;
        if (controller == null) {
            rejectCrossOpen(actor, CrossServerMutationController.OpenResult.UNAVAILABLE);
            return;
        }
        controller.prepareOpen(ownerUuid, actorUuid, page, request, outcome -> {
            if (!isCurrentOpenRequest(actorUuid, request) || !actor.isOnline()) {
                if (outcome.sessionId() != null) controller.closeSession(outcome.sessionId());
                return;
            }
            if (outcome.result() != CrossServerMutationController.OpenResult.GRANTED) {
                rejectCrossOpen(actor, outcome.result());
                return;
            }
            OpenAttempt attempt = registry.beginOpenCrossServer(
                    ownerUuid, actorUuid, vaultId, outcome.sessionId(), outcome.fence());
            handleOpenAttempt(actor, ownerUuid, ownerName, page, adminView, request, attempt, outcome.sessionId());
        });
    }

    private void handleOpenAttempt(
            Player actor, UUID ownerUuid, String ownerName, int page, boolean adminView,
            long request, OpenAttempt attempt, UUID acquiredCrossSession) {
        String vaultId = String.valueOf(page);
        if (attempt instanceof OpenAttempt.Created created) {
            proceedToLoad(actor, created.session(), ownerName, page, adminView, request);
        } else if (attempt instanceof OpenAttempt.Supersede supersede) {
            Runnable resolve = () -> resolveSupersede(actor, supersede.previous(), request,
                    () -> beginOpenAndLoad(actor, ownerUuid, ownerName, page, adminView, request));
            if (acquiredCrossSession != null && crossServer != null) {
                crossServer.closeSession(acquiredCrossSession, resolve);
            } else {
                resolve.run();
            }
        } else if (attempt instanceof OpenAttempt.Rejected rejected) {
            if (acquiredCrossSession != null && crossServer != null) {
                crossServer.closeSession(acquiredCrossSession);
            }
            txService.fireConcurrentSessionConflict(
                    ownerUuid, actor.getUniqueId(), vaultId, rejected.existing());
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
                CrossServerMutationController controller = crossServer;
                registry.close(prev.getSessionId());
                if (prev.isCrossServer() && controller != null) {
                    controller.closeSession(prev.getSessionId(),
                            () -> runIfCurrentOpenRequest(actor.getUniqueId(), request, retry));
                } else {
                    runIfCurrentOpenRequest(actor.getUniqueId(), request, retry);
                }
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
                    runAfterCrossRelease(prev,
                            () -> runIfCurrentOpenRequest(actor.getUniqueId(), request, retry));
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
        Storage.PageRecord cached = session.isCrossServer() ? null : cachedPage(key);
        if (cached != null) {
            applyLoadedRecordOnMain(actor, session, ownerName, page, adminView, cached, request);
            return;
        }
        runStorageAsync(() -> {
            Storage.PageRecord record = storage.loadPageWithRevision(ownerUuid, page);
            applyLoadedRecordOnMain(actor, session, ownerName, page, adminView, record, request);
        });
    }

    void applyLoadedRecordOnMain(Player actor, VaultSession session, String ownerName, int page,
                                 boolean adminView, Storage.PageRecord record, long request) {
        runOnMainThread(() -> {
            CrossServerMutationController controller = crossServer;
            if (!isCurrentOpenRequest(actor.getUniqueId(), request)
                    || (session.isCrossServer() && (controller == null
                    || !controller.mayUseView(session.getSessionId(), session.getNetworkFence())))) {
                if (controller != null && session.isCrossServer()) controller.closeSession(session.getSessionId());
                registry.close(session.getSessionId());
                return;
            }
            Storage.PageRecord selected = session.isCrossServer() ? record
                    : cacheLatest(new VaultKey(session.getOwnerUuid(), session.getVaultId()), record);
            applyLoadedRecord(actor, session, ownerName, page, adminView, selected);
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
        if (rejectMaintenance(admin)) return;
        runOnMainThread(() -> openBackupListOnMain(admin, targetUuid, targetName));
    }

    private void openBackupListOnMain(Player admin, UUID targetUuid, String targetName) {
        UUID actorUuid = admin.getUniqueId();
        long request = beginOpenRequest(actorUuid);
        closeCurrentVaultThenRun(admin, false, request, () -> {
            clearBackupBrowse(actorUuid);
            runStorageAsync(() -> {
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
        if (rejectMaintenance(admin)) return;
        runOnMainThread(() -> openBackupPreviewDirectOnMain(admin, targetUuid, targetName, backupId));
    }

    private void openBackupPreviewDirectOnMain(Player admin, UUID targetUuid, String targetName, int backupId) {
        UUID actorUuid = admin.getUniqueId();
        long request = beginOpenRequest(actorUuid);
        closeCurrentVaultThenRun(admin, false, request, () -> {
            clearBackupBrowse(actorUuid);
            runStorageAsync(() -> {
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
        runStorageAsync(() -> {
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
        if (crossServerRequired) {
            admin.sendMessage(config.msg("cross-server-admin-write-disabled"));
            return;
        }
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

        runStorageAsync(() -> {
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
            runAfterCrossRelease(vs,
                    () -> runIfCurrentOpenRequest(actorUuid, request, continuation));
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

    private void finishCrossReopen(Player actor, OpenSession current, VaultSession session) {
        registry.close(session.getSessionId());
        txService.fireClosed(session, CloseReason.REOPEN);
        actor.closeInventory();
        openByPlayer.remove(actor.getUniqueId(), current);
    }

    private void runAfterCrossRelease(VaultSession session, Runnable continuation) {
        CrossServerMutationController controller = crossServer;
        if (session.isCrossServer() && controller != null) controller.closeSession(session.getSessionId(), continuation);
        else continuation.run();
    }

    public void markDirtyNextTick(OpenSession session) {
        VaultSession vault = session == null ? null : session.getVaultSession();
        if (vault == null || session.isReadOnly() || session.getPage() < 1) return;
        UUID sessionId = vault.getSessionId();
        dirtySessions.add(sessionId);
        if (!dirtyCaptureScheduled.add(sessionId)) return;
        runNextTick(() -> {
            dirtyCaptureScheduled.remove(sessionId);
            flushDirty(session);
        });
    }

    private void flushDirty(OpenSession session) {
        VaultSession vault = session.getVaultSession();
        if (vault == null || !dirtySessions.contains(vault.getSessionId())) return;
        if (vault.getState() == SessionState.COMMITTING) {
            txService.afterCurrentCommit(vault, () -> flushDirty(session));
            return;
        }
        if (vault.getState() != SessionState.ACTIVE) return;
        if (vault.isCrossServer() && (crossServer == null
                || !crossServer.mayUseView(vault.getSessionId(), vault.getNetworkFence()))) {
            dirtySessions.remove(vault.getSessionId());
            Player actor = plugin.getServer().getPlayer(vault.getActorUuid());
            if (actor != null) {
                actor.sendMessage(config.msg("cross-server-unavailable"));
                actor.closeInventory();
            }
            return;
        }
        dirtySessions.remove(vault.getSessionId());
        ItemStack[] current = EnderchestGui.extractContent(session.getInventory());
        txService.commitIfActive(vault, session.getOriginalSnapshot(), current, outcome -> {
            applyCommitOutcome(vault, outcome, current);
            if (outcome == CommitOutcome.COMMITTED || outcome == CommitOutcome.NO_CHANGE) {
                session.updateOriginalSnapshot(current);
                if (dirtySessions.contains(vault.getSessionId())) flushDirty(session);
            } else if (outcome == CommitOutcome.CONFLICT) {
                Player actor = plugin.getServer().getPlayer(vault.getActorUuid());
                if (actor != null) {
                    rollbackRejectedTransfer(actor, session.getOriginalSnapshot(), current);
                    actor.sendMessage(config.msg("vault-conflict-reverted"));
                    actor.closeInventory();
                }
            }
        });
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
                runAfterCrossRelease(vs, () -> {});
            }
        });
    }

    /**
     * Autosave tick. Must be invoked on the main thread (it reads live {@code Inventory} contents);
     * each session's actual DB write is still dispatched asynchronously by
     * {@link VaultTransactionService#commitIfActive}.
     */
    public void saveAllDirty() {
        if (storageGate.isMaintenance()) return;
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
                runAfterCrossRelease(vs, () -> {});
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
        if (storageGate.isMaintenance()) return;
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
                runAfterCrossRelease(vs, () -> {});
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

    public boolean isCrossServer(OpenSession session) {
        return session != null && session.getVaultSession() != null && session.getVaultSession().isCrossServer();
    }

    public boolean isCrossServerModeRequired() {
        return crossServerRequired;
    }

    private boolean rejectMaintenance(Player player) {
        if (!storageGate.isMaintenance()) return false;
        player.sendMessage(config.msg("storage-maintenance"));
        config.playSound(player, "denied");
        return true;
    }

    public boolean crossNavigationAllowed(OpenSession session) {
        if (!isCrossServer(session) || crossServer == null) return !isCrossServer(session);
        VaultSession vault = session.getVaultSession();
        return !crossServer.hasInFlight(vault.getSessionId())
                && crossServer.mayUseView(vault.getSessionId(), vault.getNetworkFence());
    }

    public boolean crossMutationPending(OpenSession session) {
        return isCrossServer(session) && crossServer != null
                && crossServer.hasInFlight(session.getVaultSession().getSessionId());
    }

    public CrossInteractionResult submitCrossClick(
            Player actor, OpenSession session, boolean clickedTop, int slot,
            InventoryAction reportedAction, ClickType click, int hotbarSlot) {
        if (!isExactCrossView(actor, session)) return CrossInteractionResult.STALE;
        CrossServerMutationController controller = crossServer;
        if (controller == null) return CrossInteractionResult.FROZEN;
        CursorEscrow escrow = controller.cursorEscrow(session.getVaultSession().getSessionId());
        ItemStack actualCursor = actor.getItemOnCursor();
        ItemStack canonicalCursor;
        if (escrow == null) {
            if (!empty(actualCursor)) return CrossInteractionResult.STALE;
            canonicalCursor = null;
        } else {
            if (!escrowProjection.matches(actualCursor, escrow)) return CrossInteractionResult.STALE;
            canonicalCursor = escrowProjection.canonicalItem(escrow);
        }
        ItemStack target = clickedTop
                ? (slot >= 0 && slot < 45 ? session.getInventory().getItem(slot) : null)
                : (slot >= 0 && slot < actor.getInventory().getSize()
                ? actor.getInventory().getItem(slot) : null);
        InventoryAction action = normalizeCrossAction(click, canonicalCursor, target, reportedAction);
        if (action == null) return CrossInteractionResult.UNSUPPORTED;
        CrossServerInventoryPlanner.ClickInput input = new CrossServerInventoryPlanner.ClickInput(
                EnderchestGui.extractContent(session.getInventory()), actor.getInventory().getContents(),
                canonicalCursor, clickedTop, slot, action, hotbarSlot);
        CrossServerInventoryPlanner.CursorActionPlan cursorPlan = crossServerPlanner.cursorClick(input).orElse(null);
        if (cursorPlan == null) {
            ItemStack source = clickedTop && slot >= 0 && slot < 45
                    ? session.getInventory().getItem(slot) : null;
            return !empty(source) && isWithdrawal(action)
                    ? CrossInteractionResult.NO_SPACE : CrossInteractionResult.UNSUPPORTED;
        }
        if (escrow != null || !cursorPlan.cursorAfter().isEmpty()) {
            return CrossInteractionResult.from(controller.submitCursor(session.getVaultSession(), cursorPlan));
        }
        PlannedMutation plan = crossServerPlanner.click(input).orElse(null);
        return plan == null ? CrossInteractionResult.UNSUPPORTED
                : CrossInteractionResult.from(controller.submit(session.getVaultSession(), plan));
    }

    private InventoryAction normalizeCrossAction(ClickType click, ItemStack cursor,
                                                   ItemStack target, InventoryAction reported) {
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            return InventoryAction.MOVE_TO_OTHER_INVENTORY;
        }
        if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            return InventoryAction.HOTBAR_SWAP;
        }
        if (click == ClickType.DOUBLE_CLICK) return InventoryAction.COLLECT_TO_CURSOR;
        if (click == ClickType.LEFT) {
            if (empty(cursor)) return InventoryAction.PICKUP_ALL;
            return empty(target) || cursor.isSimilar(target)
                    ? InventoryAction.PLACE_ALL : InventoryAction.SWAP_WITH_CURSOR;
        }
        if (click == ClickType.RIGHT) {
            if (empty(cursor)) return InventoryAction.PICKUP_HALF;
            return empty(target) || cursor.isSimilar(target)
                    ? InventoryAction.PLACE_ONE : InventoryAction.SWAP_WITH_CURSOR;
        }
        return reported == InventoryAction.NOTHING ? null : null;
    }

    public CrossInteractionResult submitCrossDrag(
            Player actor, OpenSession session, ItemStack oldCursor, DragType dragType,
            Set<Integer> topSlots, Set<Integer> playerSlots) {
        if (!isExactCrossView(actor, session)) return CrossInteractionResult.STALE;
        CrossServerMutationController controller = crossServer;
        if (controller == null) return CrossInteractionResult.FROZEN;
        CursorEscrow escrow = controller.cursorEscrow(session.getVaultSession().getSessionId());
        if (escrow == null || !escrowProjection.matches(oldCursor, escrow)) {
            return CrossInteractionResult.STALE;
        }
        ItemStack canonical = escrowProjection.canonicalItem(escrow);
        Map<Integer, ItemStack> canonicalTop = new java.util.LinkedHashMap<>();
        Map<Integer, ItemStack> canonicalPlayer = new java.util.LinkedHashMap<>();
        List<DragTarget> targets = new java.util.ArrayList<>();
        for (int slot : new TreeSet<>(topSlots)) {
            ItemStack current = session.getInventory().getItem(slot);
            if (empty(current) || current.isSimilar(canonical)) targets.add(new DragTarget(true, slot, current));
        }
        for (int slot : new TreeSet<>(playerSlots)) {
            ItemStack current = actor.getInventory().getItem(slot);
            if (empty(current) || current.isSimilar(canonical)) targets.add(new DragTarget(false, slot, current));
        }
        if (targets.isEmpty()) return CrossInteractionResult.UNSUPPORTED;
        int remaining = canonical.getAmount();
        int even = dragType == DragType.EVEN ? remaining / targets.size() : 1;
        if (even <= 0) return CrossInteractionResult.UNSUPPORTED;
        for (DragTarget target : targets) {
            int before = empty(target.current()) ? 0 : target.current().getAmount();
            int moved = Math.min(remaining, Math.min(even, canonical.getMaxStackSize() - before));
            if (moved <= 0) continue;
            ItemStack after = empty(target.current()) ? canonical.clone() : target.current().clone();
            after.setAmount(before + moved);
            (target.top() ? canonicalTop : canonicalPlayer).put(target.slot(), after);
            remaining -= moved;
            if (remaining == 0) break;
        }
        if (remaining == canonical.getAmount()) return CrossInteractionResult.UNSUPPORTED;
        ItemStack canonicalRemaining = remaining == 0 ? null : canonical.clone();
        if (canonicalRemaining != null) canonicalRemaining.setAmount(remaining);
        CrossServerInventoryPlanner.CursorActionPlan plan = crossServerPlanner.cursorDrag(
                new CrossServerInventoryPlanner.DragInput(
                EnderchestGui.extractContent(session.getInventory()), actor.getInventory().getContents(),
                escrowProjection.canonicalItem(escrow), canonicalRemaining,
                canonicalTop, canonicalPlayer)).orElse(null);
        return plan == null ? CrossInteractionResult.UNSUPPORTED
                : CrossInteractionResult.from(controller.submitCursor(session.getVaultSession(), plan));
    }

    private record DragTarget(boolean top, int slot, ItemStack current) {}

    public void rejectFrozenCrossView(Player actor, OpenSession session) {
        if (!isExactCrossView(actor, session)) return;
        VaultSession vault = session.getVaultSession();
        openByPlayer.remove(actor.getUniqueId(), session);
        registry.close(vault.getSessionId());
        if (crossServer != null) crossServer.closeSession(vault.getSessionId());
        actor.closeInventory();
        actor.sendMessage(config.msg("cross-server-unavailable"));
        config.playSound(actor, "denied");
    }

    /** InventoryClose fires before NMS removed(); erase only the exact volatile projection. */
    public void detachCrossCursorBeforeClose(Player actor, Inventory closing) {
        OpenSession session = openByPlayer.get(actor.getUniqueId());
        if (session == null || !session.getInventory().equals(closing) || !isCrossServer(session)
                || crossServer == null) return;
        VaultSession vault = session.getVaultSession();
        CursorEscrow escrow = crossServer.cursorEscrow(vault.getSessionId());
        if (escrow == null) return;
        if (crossServer.cursorDetached(vault.getSessionId()) && crossCursorEmpty(actor.getItemOnCursor())) {
            return;
        }
        if (!escrowProjection.matches(actor.getItemOnCursor(), escrow)) {
            crossServer.quarantineCursor(vault.getSessionId());
            return;
        }
        actor.setItemOnCursor(null);
        actor.updateInventory();
        if (!crossServer.markCursorDetached(vault.getSessionId(), escrow)) {
            crossServer.quarantineCursor(vault.getSessionId());
            return;
        }
        MutationPlan settlement;
        try {
            settlement = playerData.planEscrowSettlement(actor.getUniqueId(), escrow);
        } catch (RuntimeException divergence) {
            crossServer.quarantineCursor(vault.getSessionId());
            return;
        }
        if (settlement == null || crossServer.submitDetachedSettlement(vault, settlement,
                VaultPayloadCodec.encode(EnderchestGui.extractContent(session.getInventory())))
                != CrossServerMutationController.SubmitResult.ACCEPTED) {
            // Full/changed inventory: keep CURSOR_STABLE durable for a later recovery attempt.
            crossServer.abandonSession(vault.getSessionId());
        }
    }

    public void detachCrossCursorBeforeDisconnect(Player actor) {
        OpenSession session = openByPlayer.get(actor.getUniqueId());
        if (session != null) detachCrossCursorBeforeClose(actor, session.getInventory());
    }

    /** Death must not add an escrow item after Paper already calculated its drop list. */
    public void parkCrossCursorBeforeDeath(Player actor) {
        OpenSession session = openByPlayer.get(actor.getUniqueId());
        if (session == null || !isCrossServer(session) || crossServer == null) return;
        VaultSession vault = session.getVaultSession();
        CursorEscrow escrow = crossServer.cursorEscrow(vault.getSessionId());
        if (escrow == null) return;
        if (!crossServer.cursorDetached(vault.getSessionId())) {
            if (!escrowProjection.matches(actor.getItemOnCursor(), escrow)) {
                crossServer.quarantineCursor(vault.getSessionId());
                return;
            }
            actor.setItemOnCursor(null);
            actor.updateInventory();
            if (!crossServer.markCursorDetached(vault.getSessionId(), escrow)) {
                crossServer.quarantineCursor(vault.getSessionId());
                return;
            }
        }
        crossServer.abandonSession(vault.getSessionId());
    }

    @Override
    public CrossServerMutationController.ApplyResult reserve(
            CrossServerMutationController.ViewIdentity view, PlannedMutation plan) {
        OpenSession session = exactCrossView(view);
        if (session == null) return CrossServerMutationController.ApplyResult.STALE_VIEW;
        if (!plan.vaultBefore().equals(VaultPayloadCodec.encode(
                EnderchestGui.extractContent(session.getInventory())))) {
            return CrossServerMutationController.ApplyResult.DIVERGED;
        }
        return compareApplyAndSave(view.actorUuid(), plan.playerPlan(), MutationPlan.Phase.BEFORE,
                MutationPlan.Phase.RESERVED);
    }

    @Override
    public CursorEscrow createEscrow(UUID mutationId, long opSequence, SlotValue canonical,
                                     List<com.valerin.venderchest.crossserver.SlotMutation> fallback) {
        SlotValue projection = escrowProjection.projectionValue(canonical, mutationId, opSequence);
        return new CursorEscrow(mutationId, opSequence, canonical, projection, fallback);
    }

    @Override
    public CrossServerMutationController.ApplyResult restoreBefore(
            CrossServerMutationController.ViewIdentity view, PlannedMutation plan) {
        try {
            if (!playerData.isOnline(view.actorUuid())) return CrossServerMutationController.ApplyResult.OFFLINE;
            Map<SlotRef, SlotValue> observed = playerData.snapshot(view.actorUuid(), plan.playerPlan());
            if (plan.playerPlan().matches(MutationPlan.Phase.BEFORE, observed)) {
                return CrossServerMutationController.ApplyResult.OK;
            }
            if (!plan.playerPlan().matches(MutationPlan.Phase.RESERVED, observed)
                    || !playerData.compareAndApply(view.actorUuid(), plan.playerPlan(), observed,
                    MutationPlan.Phase.BEFORE)) {
                return CrossServerMutationController.ApplyResult.DIVERGED;
            }
            playerData.saveData(view.actorUuid());
            updatePlayerInventory(view.actorUuid());
            return CrossServerMutationController.ApplyResult.OK;
        } catch (IllegalStateException offline) {
            return CrossServerMutationController.ApplyResult.OFFLINE;
        } catch (Exception failed) {
            return CrossServerMutationController.ApplyResult.SAVE_FAILED;
        }
    }

    private void switchCrossServerPage(Player actor, OpenSession current, int page) {
        VaultSession previous = current.getVaultSession();
        if (previous == null || page == current.getPage() || crossServer == null
                || !crossNavigationAllowed(current)) return;
        if (!crossCursorEmpty(actor.getItemOnCursor())) {
            actor.sendMessage(config.msg("cross-server-cursor-not-empty"));
            config.playSound(actor, "denied");
            return;
        }
        if (registry.current(new VaultKey(previous.getOwnerUuid(), String.valueOf(page))).isPresent()) {
            actor.sendMessage(config.msg("vault-busy"));
            return;
        }
        long request = beginOpenRequest(actor.getUniqueId());
        if (!crossServer.rebindView(previous.getSessionId(), page, request)) {
            rejectFrozenCrossView(actor, current);
            return;
        }
        VaultSession next = registry.switchCrossServerPage(
                previous.getSessionId(), String.valueOf(page)).orElse(null);
        if (next == null) {
            crossServer.rebindView(previous.getSessionId(), current.getPage(), request);
            actor.sendMessage(config.msg("vault-busy"));
            return;
        }
        openByPlayer.remove(actor.getUniqueId(), current);
        txService.fireClosed(previous, CloseReason.REOPEN);
        actor.closeInventory();
        proceedToLoad(actor, next, current.getTargetName(), page, current.isAdminView(), request);
    }

    @Override
    public CrossServerMutationController.ApplyResult applyCommitted(
            CrossServerMutationController.ViewIdentity view, PlannedMutation plan, long newRevision) {
        CrossServerMutationController.ApplyResult playerResult = applyCommittedPlayer(view.actorUuid(), plan.playerPlan());
        if (playerResult != CrossServerMutationController.ApplyResult.OK) return playerResult;

        OpenSession session = exactCrossView(view);
        if (session == null) return CrossServerMutationController.ApplyResult.OK;
        if (!plan.vaultBefore().equals(VaultPayloadCodec.encode(
                EnderchestGui.extractContent(session.getInventory())))) {
            return CrossServerMutationController.ApplyResult.DIVERGED;
        }
        ItemStack[] after = VaultPayloadCodec.decode(plan.vaultAfter());
        if (after.length != 45) return CrossServerMutationController.ApplyResult.DIVERGED;
        for (int slot = 0; slot < after.length; slot++) {
            session.getInventory().setItem(slot, after[slot] == null ? null : after[slot].clone());
        }
        if (!plan.vaultBefore().equals(plan.vaultAfter())) {
            if (!registry.advanceNetworkRevision(view.sessionId(), newRevision - 1, newRevision)) {
                return CrossServerMutationController.ApplyResult.DIVERGED;
            }
            session.updateOriginalSnapshot(cloneArray(after));
        }
        if (plan.playerPlan().isCursorStable()) {
            Player actor = plugin.getServer().getPlayer(view.actorUuid());
            if (actor == null || !crossCursorEmpty(actor.getItemOnCursor())) {
                return CrossServerMutationController.ApplyResult.DIVERGED;
            }
            actor.setItemOnCursor(escrowProjection.project(plan.playerPlan().escrow().canonical(),
                    plan.playerPlan().escrow().escrowId(), plan.playerPlan().escrow().opSequence()));
        }
        updatePlayerInventory(view.actorUuid());
        return CrossServerMutationController.ApplyResult.OK;
    }

    @Override
    public CrossServerMutationController.ApplyResult applySettlementPlayer(
            CrossServerMutationController.ViewIdentity view, MutationPlan plan) {
        OpenSession session = exactCrossView(view);
        Player actor = plugin.getServer().getPlayer(view.actorUuid());
        boolean detachedFallback = plan.settlement().kind() == com.valerin.venderchest.crossserver.CursorSettlement.Kind.FALLBACK;
        if (session == null || actor == null || (detachedFallback
                ? !crossCursorEmpty(actor.getItemOnCursor())
                : !escrowProjection.matches(actor.getItemOnCursor(), plan.escrow()))) {
            return CrossServerMutationController.ApplyResult.STALE_VIEW;
        }
        return compareApplyAndSave(view.actorUuid(), plan, MutationPlan.Phase.BEFORE,
                MutationPlan.Phase.AFTER);
    }

    @Override
    public CrossServerMutationController.ApplyResult applySettlementCommitted(
            CrossServerMutationController.ViewIdentity view, PlannedMutation committed, long newRevision) {
        MutationPlan plan = committed.playerPlan();
        OpenSession session = exactCrossView(view);
        if (session == null) return CrossServerMutationController.ApplyResult.OK;
        Player actor = plugin.getServer().getPlayer(view.actorUuid());
        boolean detachedFallback = plan.settlement().kind() == com.valerin.venderchest.crossserver.CursorSettlement.Kind.FALLBACK;
        if (actor == null || (detachedFallback ? !crossCursorEmpty(actor.getItemOnCursor())
                : !escrowProjection.matches(actor.getItemOnCursor(), plan.escrow()))
                || !committed.vaultBefore().equals(VaultPayloadCodec.encode(
                EnderchestGui.extractContent(session.getInventory())))) {
            return CrossServerMutationController.ApplyResult.DIVERGED;
        }
        if (!committed.vaultBefore().equals(committed.vaultAfter())) {
            ItemStack[] after = VaultPayloadCodec.decode(committed.vaultAfter());
            if (after.length != 45) return CrossServerMutationController.ApplyResult.DIVERGED;
            for (int slot = 0; slot < after.length; slot++) {
                session.getInventory().setItem(slot, after[slot] == null ? null : after[slot].clone());
            }
            if (!registry.advanceNetworkRevision(view.sessionId(), newRevision - 1, newRevision)) {
                return CrossServerMutationController.ApplyResult.DIVERGED;
            }
            session.updateOriginalSnapshot(cloneArray(after));
        }
        CursorEscrow next = plan.settlement().nextEscrow();
        actor.setItemOnCursor(next == null ? null : escrowProjection.project(
                next.canonical(), next.escrowId(), next.opSequence()));
        actor.updateInventory();
        return CrossServerMutationController.ApplyResult.OK;
    }

    @Override
    public void failClosed(CrossServerMutationController.ViewIdentity view,
                           CrossServerMutationController.Failure failure) {
        OpenSession session = exactCrossView(view);
        registry.close(view.sessionId());
        if (session == null) return;
        openByPlayer.remove(view.actorUuid(), session);
        Player actor = plugin.getServer().getPlayer(view.actorUuid());
        if (actor != null) {
            actor.closeInventory();
            actor.sendMessage(config.msg("cross-server-failed"));
            config.playSound(actor, "denied");
        }
    }

    private CrossServerMutationController.ApplyResult compareApplyAndSave(
            UUID actorUuid, MutationPlan plan, MutationPlan.Phase expected, MutationPlan.Phase target) {
        try {
            if (!playerData.isOnline(actorUuid)) return CrossServerMutationController.ApplyResult.OFFLINE;
            if (plan.playerSlots().isEmpty()) return CrossServerMutationController.ApplyResult.OK;
            Map<SlotRef, SlotValue> observed = playerData.snapshot(actorUuid, plan);
            if (!plan.matches(expected, observed)
                    || !playerData.compareAndApply(actorUuid, plan, observed, target)) {
                return CrossServerMutationController.ApplyResult.DIVERGED;
            }
            playerData.saveData(actorUuid);
            updatePlayerInventory(actorUuid);
            return CrossServerMutationController.ApplyResult.OK;
        } catch (IllegalStateException offline) {
            return CrossServerMutationController.ApplyResult.OFFLINE;
        } catch (Exception failed) {
            return CrossServerMutationController.ApplyResult.SAVE_FAILED;
        }
    }

    private CrossServerMutationController.ApplyResult applyCommittedPlayer(UUID actorUuid, MutationPlan plan) {
        try {
            if (!playerData.isOnline(actorUuid)) return CrossServerMutationController.ApplyResult.OFFLINE;
            if (plan.playerSlots().isEmpty()) return CrossServerMutationController.ApplyResult.OK;
            Map<SlotRef, SlotValue> observed = playerData.snapshot(actorUuid, plan);
            if (plan.matches(MutationPlan.Phase.AFTER, observed)) {
                return CrossServerMutationController.ApplyResult.OK;
            }
            if ((!plan.matches(MutationPlan.Phase.BEFORE, observed)
                    && !plan.matches(MutationPlan.Phase.RESERVED, observed))
                    || !playerData.compareAndApply(actorUuid, plan, observed, MutationPlan.Phase.AFTER)) {
                return CrossServerMutationController.ApplyResult.DIVERGED;
            }
            playerData.saveData(actorUuid);
            updatePlayerInventory(actorUuid);
            return CrossServerMutationController.ApplyResult.OK;
        } catch (IllegalStateException offline) {
            return CrossServerMutationController.ApplyResult.OFFLINE;
        } catch (Exception failed) {
            return CrossServerMutationController.ApplyResult.SAVE_FAILED;
        }
    }

    private void updatePlayerInventory(UUID actorUuid) {
        Player actor = plugin.getServer().getPlayer(actorUuid);
        if (actor != null) actor.updateInventory();
    }

    private boolean isExactCrossView(Player actor, OpenSession session) {
        VaultSession vault = session == null ? null : session.getVaultSession();
        return vault != null && vault.isCrossServer() && crossServer != null
                && openByPlayer.get(actor.getUniqueId()) == session
                && actor.getOpenInventory().getTopInventory().equals(session.getInventory())
                && registry.isActive(vault.getSessionId(), session.getInventory())
                && crossServer.mayUseView(vault.getSessionId(), vault.getNetworkFence());
    }

    private OpenSession exactCrossView(CrossServerMutationController.ViewIdentity view) {
        OpenSession session = openByPlayer.get(view.actorUuid());
        if (session == null || session.getPage() != view.page()
                || !isCurrentOpenRequest(view.actorUuid(), view.actorRequest())) return null;
        VaultSession vault = session.getVaultSession();
        if (vault == null || !vault.getSessionId().equals(view.sessionId())
                || !vault.getOwnerUuid().equals(view.ownerUuid())
                || !vault.getActorUuid().equals(view.actorUuid())
                || vault.getNetworkFence() != view.fence()) return null;
        Player actor = plugin.getServer().getPlayer(view.actorUuid());
        return actor != null && actor.isOnline()
                && actor.getOpenInventory().getTopInventory().equals(session.getInventory())
                && registry.isActive(view.sessionId(), session.getInventory()) ? session : null;
    }

    private void rejectCrossOpen(Player actor, CrossServerMutationController.OpenResult result) {
        String key = switch (result) {
            case BUSY -> "vault-busy";
            case RECOVERY_PENDING -> "cross-server-recovery-pending";
            case QUARANTINED -> "cross-server-quarantined";
            default -> "cross-server-unavailable";
        };
        actor.sendMessage(config.msg(key));
        config.playSound(actor, "denied");
    }

    private boolean hasLegacyEscrowTag(Player actor) {
        try {
            if (escrowProjection.tag(actor.getItemOnCursor()).isPresent()) return true;
            for (ItemStack item : actor.getInventory().getContents()) {
                if (escrowProjection.tag(item).isPresent()) return true;
            }
            return false;
        } catch (RuntimeException malformedTag) {
            return true;
        }
    }

    private static boolean isWithdrawal(InventoryAction action) {
        return action == InventoryAction.PICKUP_ALL
                || action == InventoryAction.PICKUP_HALF
                || action == InventoryAction.PICKUP_ONE
                || action == InventoryAction.PICKUP_SOME
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    static boolean crossCursorEmpty(ItemStack item) {
        return item == null || crossCursorEmpty(item.getType().isAir(), item.getAmount());
    }

    static boolean crossCursorEmpty(boolean air, int amount) {
        return air || amount <= 0;
    }

    public enum CrossInteractionResult {
        ACCEPTED, BUSY, FROZEN, STALE, NO_SPACE, UNSUPPORTED;

        private static CrossInteractionResult from(CrossServerMutationController.SubmitResult result) {
            return valueOf(result.name());
        }
    }

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

    private void runStorageAsync(Runnable task) {
        if (!storageGate.tryBegin()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    task.run();
                } finally {
                    storageGate.end();
                }
            });
        } catch (RuntimeException error) {
            storageGate.end();
            throw error;
        }
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
