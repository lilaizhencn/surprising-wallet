package com.surprising.wallet.common.approval;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** One-way request to disable an independently registered MPC key. */
public record ApprovalKeyQuarantine(UUID tenantId, UUID keyId, UUID actorId, Instant quarantinedAt, String reason) {
    public ApprovalKeyQuarantine {
        if (tenantId == null || keyId == null || actorId == null || quarantinedAt == null
                || reason == null || reason.strip().length() < 8 || reason.length() > 500) {
            throw new IllegalArgumentException("bounded owned key quarantine required");
        }
    }

    public String hash() {
        return SigningAuthorization.hash(("SW-KEY-QUARANTINE-V1:" + tenantId + ":" + keyId + ":"
                + actorId + ":" + quarantinedAt + ":" + reason).getBytes(StandardCharsets.UTF_8));
    }

    public record Receipt(UUID tenantId, UUID keyId, String descriptorHash) {
        public Receipt {
            if (tenantId == null || keyId == null || descriptorHash == null || !descriptorHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid key quarantine receipt");
            }
        }
    }
}
