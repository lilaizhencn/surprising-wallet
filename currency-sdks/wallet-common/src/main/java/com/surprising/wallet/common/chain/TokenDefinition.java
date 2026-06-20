package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Registry entry for chain token contracts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenDefinition implements Serializable {
    private Long id;
    private String chain;
    private String symbol;
    private String contractAddress;
    private Integer decimals;
    private String standard;
    private Boolean nativeAsset;
    private Boolean active;
}
