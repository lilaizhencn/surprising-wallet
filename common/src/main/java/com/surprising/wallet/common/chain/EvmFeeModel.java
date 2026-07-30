package com.surprising.wallet.common.chain;

import java.util.Locale;

/**
 * EVM 链的总费用模型。
 *
 * <p>该模型只描述执行费之外的链级费用组成，不描述 Gas 币种，也不描述交易信封类型。
 * Gas 币种由 {@link AccountChainProfile#getNativeSymbol()} 决定，交易信封由
 * {@link AccountChainProfile#getGasPolicy()} 决定。</p>
 */
public enum EvmFeeModel {
    STANDARD("standard"),
    OP_STACK("op-stack"),
    OP_STACK_L1("op-stack-l1"),
    ARBITRUM_NITRO("arbitrum-nitro"),
    SCROLL("scroll");

    private final String configValue;

    EvmFeeModel(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean hasSeparateL1Fee() {
        return this == OP_STACK || this == OP_STACK_L1 || this == SCROLL;
    }

    public boolean hasOperatorFee() {
        return this == OP_STACK;
    }

    public static EvmFeeModel parse(String value) {
        String normalized = value == null || value.isBlank()
                ? STANDARD.configValue
                : value.trim().toLowerCase(Locale.ROOT);
        for (EvmFeeModel model : values()) {
            if (model.configValue.equals(normalized)) {
                return model;
            }
        }
        throw new IllegalArgumentException("unsupported EVM fee model: " + value);
    }
}
