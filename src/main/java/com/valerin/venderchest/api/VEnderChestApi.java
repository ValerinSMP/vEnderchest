package com.valerin.venderchest.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only public surface for observing vEnderchest vault sessions and content. Obtain it via
 * {@code Bukkit.getServicesManager().load(VEnderChestApi.class)}.
 *
 * <p>Every DTO returned here is immutable and every {@code ItemStack} is a defensive clone — this
 * API cannot be used to mutate a vault, cancel a transaction, or otherwise influence vault
 * behavior in any way. It exists purely for observation; see {@code com.valerin.venderchest.api.event}
 * for the corresponding non-cancellable Bukkit events. vEnderchest's duplication protection works
 * identically whether or not anything is registered as a consumer of this API.
 */
public interface VEnderChestApi {

    /** The session, if any, currently held by this actor (owner or admin) across all vaults. */
    Optional<VaultSessionView> activeSession(UUID actorUuid);

    /** Every vault session currently ACTIVE on this server. */
    Collection<VaultSessionView> activeSessions();

    /**
     * A read-only, point-in-time snapshot of one vault's persisted content and revision.
     * <p><b>Threading:</b> this performs synchronous database I/O — do not call it from the main
     * server thread.
     */
    Optional<VaultSnapshot> snapshot(UUID ownerUuid, String vaultId);
}
