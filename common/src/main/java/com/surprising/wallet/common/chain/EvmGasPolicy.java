package com.surprising.wallet.common.chain;

import java.util.Locale;

/** EVM 交易信封与 Gas 报价策略。 */
public enum EvmGasPolicy {
    /**
     * 定义 {@code LEGACY_GAS_PRICE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    LEGACY_GAS_PRICE("legacy-gas-price"),
    /**
     * 定义 {@code EIP1559} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    EIP1559("eip1559");

    /**
     * 保存 {@code configValue}，用于保存运行配置和策略参数。
     */
    private final String configValue;

    /**
     * 构造 {@code EvmGasPolicy}，初始化该组件运行所需的状态和依赖。
     */
    EvmGasPolicy(String configValue) {
        this.configValue = configValue;
    }

    /**
     * 执行 {@code configValue} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String configValue() {
        return configValue;
    }

    /**
     * 判断 {@code isEip1559} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEip1559() {
        return this == EIP1559;
    }

    /**
     * 解析或转换 {@code parse} 对应的数据，并校验其格式和边界。
     */
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
