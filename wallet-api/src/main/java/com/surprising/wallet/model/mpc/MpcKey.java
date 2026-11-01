package com.surprising.wallet.model.mpc;

import java.util.UUID;

/** Tenant-owned public key binding; secret shares are never wallet database data. */
public record MpcKey(UUID id, UUID tenantId, String keyGroup, long userId, int biz, long addressIndex,
                     String algorithm, State state, int activeEpoch, String publicKeyHex) {
    public enum State { CREATING, ACTIVE, REFRESHING, FAILED, RETIRED }
}
