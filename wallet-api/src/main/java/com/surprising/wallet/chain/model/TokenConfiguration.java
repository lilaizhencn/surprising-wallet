package com.surprising.wallet.chain.model;

import java.math.BigDecimal;
import java.time.Instant;

/** token_config 的完整配置行；网络、标准和各项门槛均有唯一存储值。 */
public record TokenConfiguration(
        long id, String chain, String network, String symbol, String standard,
        String contractAddress, String contractAddressBase58, String contractAddressHex,
        int decimals, boolean enabled, boolean collectEnabled,
        BigDecimal minDeposit, BigDecimal minWithdraw, BigDecimal collectThreshold,
        String gasStrategy, Integer confirmationRequired, Instant createdAt, Instant updatedAt) {
}
