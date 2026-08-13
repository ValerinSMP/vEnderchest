package com.valerin.venderchest.crossserver;

import java.util.Objects;

public record PlannedMutation(MutationPlan playerPlan, String vaultBefore, String vaultAfter) {
    public PlannedMutation {
        Objects.requireNonNull(playerPlan, "playerPlan");
        Objects.requireNonNull(vaultBefore, "vaultBefore");
        Objects.requireNonNull(vaultAfter, "vaultAfter");
        if (vaultBefore.equals(vaultAfter) && playerPlan.isLegacy()) {
            throw new IllegalArgumentException("legacy mutation does not change vault");
        }
    }
}
