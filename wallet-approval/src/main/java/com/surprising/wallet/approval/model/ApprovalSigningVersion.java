package com.surprising.wallet.approval.model;

import java.util.UUID;

public record ApprovalSigningVersion(UUID id,UUID tenantId,UUID transactionId,String versionHash,
        String payloadEncoding,String payloadHex,tools.jackson.databind.JsonNode chainEvidence) { }
