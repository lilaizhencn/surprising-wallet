package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionCandidateRecord {
    private UUID tenantId;
    private UUID custodyAddressId;
    private String chain;
    private String assetSymbol;
    private String accountId;
    private String address;
    private String ownerAddress;
    private Long userId;
    private Integer biz;
    private Long addressIndex;
    private String walletRole;
    private BigDecimal amount;
}
