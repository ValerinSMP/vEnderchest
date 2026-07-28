package com.valerin.venderchest.session;

import java.util.UUID;

/**
 * Stable identity of "which vault": the owning player plus a stable vault id. Today {@code vaultId}
 * is always the page number stringified ({@code String.valueOf(page)}) — the honest mapping given
 * the current one-page-per-slot architecture, not a new concept.
 */
public record VaultKey(UUID ownerUuid, String vaultId) {
}
