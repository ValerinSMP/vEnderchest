package com.valerin.venderchest.api;

import java.util.UUID;

/** Builds the stable location key format used throughout the public API. */
public final class LocationKeys {

    private LocationKeys() {}

    /** {@code venderchest:<ownerUuid>:vault:<vaultId>:slot:<slot>} */
    public static String of(UUID ownerUuid, String vaultId, int slot) {
        return "venderchest:" + ownerUuid + ":vault:" + vaultId + ":slot:" + slot;
    }
}
