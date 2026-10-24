package com.surprising.wallet.model.custody;

import java.time.Instant;

/**
 * 幂等记录。
 */
public record IdempotencyRecord(
        String requestHash,
        Integer responseStatus,
        String responseJson,
        Instant expiresAt
) {
}
