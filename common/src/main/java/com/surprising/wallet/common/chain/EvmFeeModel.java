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
    /**
     * 定义 {@code STANDARD} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    STANDARD("standard"),
    /**
     * 定义 {@code OP_STACK} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    OP_STACK("op-stack"),
    /**
     * 定义 {@code OP_STACK_L1} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    OP_STACK_L1("op-stack-l1"),
    /**
     * 定义 {@code ARBITRUM_NITRO} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ARBITRUM_NITRO("arbitrum-nitro"),
    /**
     * 定义 {@code SCROLL} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SCROLL("scroll");

    /**
     * 保存 {@code configValue}，用于保存运行配置和策略参数。
     */
    private final String configValue;

    /**
     * 构造 {@code EvmFeeModel}，初始化该组件运行所需的状态和依赖。
     */
    EvmFeeModel(String configValue) {
        this.configValue = configValue;
    }

    /**
     * 执行 {@code configValue} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String configValue() {
        return configValue;
    }

    /**
     * 判断 {@code hasSeparateL1Fee} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean hasSeparateL1Fee() {
        return this == OP_STACK || this == OP_STACK_L1 || this == SCROLL;
    }

    /**
     * 判断 {@code hasOperatorFee} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean hasOperatorFee() {
        return this == OP_STACK;
    }

    /**
     * 解析或转换 {@code parse} 对应的数据，并校验其格式和边界。
     */
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
