package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainAddressRecord {
    private Long id;
    private String chain;
    private String assetSymbol;
    private String accountId;
    private Long userId;
    private Integer biz;
    private Long addressIndex;
    private String address;
    private String ownerAddress;
    private String derivationPath;
    private String walletRole;
    private Boolean enabled;
}
