package com.valerin.venderchest.api;

import java.time.Instant;
import java.util.UUID;

/** Read-only view of one currently tracked vault session. */
public record VaultSessionView(UUID sessionId, UUID ownerUuid, UUID actorUuid, String vaultId,
                                long revision, Instant openedAt, PublicSessionState state) {
}
