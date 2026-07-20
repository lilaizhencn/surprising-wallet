package com.surprising.wallet.common.key;

import java.time.OffsetDateTime;

public record WalletKeyConfig(
        String sig1Seed,
        String sig2Seed,
        String recoverySeed,
        String ed25519Seed,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String updatedBy) {
}
