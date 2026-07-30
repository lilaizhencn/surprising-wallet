package com.surprising.wallet.common.chain;

import java.util.Locale;

/** EVM 交易信封与 Gas 报价策略。 */
public enum EvmGasPolicy {
    LEGACY_GAS_PRICE("legacy-gas-price"),
    EIP1559("eip1559");

    private final String configValue;

    EvmGasPolicy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean isEip1559() {
        return this == EIP1559;
    }

    public static EvmGasPolicy parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (EvmGasPolicy policy : values()) {
            if (policy.configValue.equals(normalized)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unsupported EVM gas policy: " + value);
    }
}
