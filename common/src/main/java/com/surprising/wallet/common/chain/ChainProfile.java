package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Chain runtime profile used by adapter engines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainProfile {
    private ChainType chainType;
    private String rpcUrl;
    private Long chainId;
    private BigDecimal defaultGasLimit;
    private BigDecimal gasPriceFloor;
    private BigDecimal priorityFee;
    private Integer depositConfirmations;
    private Integer withdrawConfirmations;
    private String nativeSymbol;
}
