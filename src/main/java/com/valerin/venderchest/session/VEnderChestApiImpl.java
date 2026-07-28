package com.valerin.venderchest.session;

import com.valerin.venderchest.api.LocationKeys;
import com.valerin.venderchest.api.PublicSessionState;
import com.valerin.venderchest.api.VEnderChestApi;
import com.valerin.venderchest.api.VaultSessionView;
import com.valerin.venderchest.api.VaultSlot;
import com.valerin.venderchest.api.VaultSnapshot;
import com.valerin.venderchest.storage.Storage;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the public, read-only {@link VEnderChestApi} on top of the internal registry and
 * storage. Deliberately exposes neither directly: only immutable DTOs cross this boundary.
 */
public final class VEnderChestApiImpl implements VEnderChestApi {

    private final VaultSessionRegistry registry;
    private final Storage storage;

    public VEnderChestApiImpl(VaultSessionRegistry registry, Storage storage) {
        this.registry = registry;
        this.storage = storage;
    }

    @Override
    public Optional<VaultSessionView> activeSession(UUID actorUuid) {
        return registry.allActive().stream()
                .filter(s -> s.getActorUuid().equals(actorUuid))
                .findFirst()
                .map(VEnderChestApiImpl::toView);
    }

    @Override
    public Collection<VaultSessionView> activeSessions() {
        return registry.allActive().stream().map(VEnderChestApiImpl::toView).toList();
    }

    @Override
    public Optional<VaultSnapshot> snapshot(UUID ownerUuid, String vaultId) {
        int page;
        try {
            page = Integer.parseInt(vaultId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Storage.PageRecord record = storage.loadPageWithRevision(ownerUuid, page);
        List<VaultSlot> slots = new ArrayList<>(record.items().length);
        for (int i = 0; i < record.items().length; i++) {
            ItemStack item = record.items()[i];
            slots.add(new VaultSlot(i, item, LocationKeys.of(ownerUuid, vaultId, i)));
        }
        return Optional.of(new VaultSnapshot(ownerUuid, vaultId, record.revision(), slots));
    }

    private static VaultSessionView toView(VaultSession s) {
        return new VaultSessionView(s.getSessionId(), s.getOwnerUuid(), s.getActorUuid(), s.getVaultId(),
                s.getCurrentRevision(), s.getOpenedAt(), toPublicState(s.getState()));
    }

    private static PublicSessionState toPublicState(SessionState state) {
        return switch (state) {
            case OPENING -> PublicSessionState.OPENING;
            case ACTIVE -> PublicSessionState.ACTIVE;
            case COMMITTING -> PublicSessionState.COMMITTING;
            case CLOSED -> PublicSessionState.CLOSED;
        };
    }
}
