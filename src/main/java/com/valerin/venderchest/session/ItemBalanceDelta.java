package com.valerin.venderchest.session;

/** Net amount of one item kind gained or lost by the vault during a session. */
public record ItemBalanceDelta(boolean gainedByVault, int sourceSlot, int amount) {
}
