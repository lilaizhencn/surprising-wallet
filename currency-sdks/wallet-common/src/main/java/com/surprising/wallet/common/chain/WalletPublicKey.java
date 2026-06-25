package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletPublicKey {
    private Integer keySlot;
    private String keyRole;
    private String keyType;
    private String network;
    private String publicKey;
    private Boolean enabled;
    private String remark;
}
