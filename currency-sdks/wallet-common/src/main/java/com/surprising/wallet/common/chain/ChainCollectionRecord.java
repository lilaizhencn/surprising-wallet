package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Runtime collection transaction stored in collection_record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainCollectionRecord {
    private Long id;
    private UUID tenantId;
    private UUID custodyAddressId;
    private String collectionNo;
    private String chain;
    private String assetSymbol;
    private String fromAddress;
    private String toAddress;
    private BigDecimal amount;
    private BigDecimal fee;
    private String txHash;
    private String status;
    private String errorMessage;
    private String rawPayload;
    private Instant createdAt;
    private Instant updatedAt;
}
