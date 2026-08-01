package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.TokenDefinition;
/**
 * Aptos Fungible Asset (FA) 标准工具类。
 *
 * <p>Aptos FA 是 Move 生态中 fungible token 的统一标准，取代旧的 Coin 标准。
 * 提供 FA 代币的 metadata 地址提取和标准校验。
 */
final class AptosFungibleAsset {
    /**
     * 定义 {@code STANDARD} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final String STANDARD = "APTOS_FA";
    /**
     * 构造 {@code AptosFungibleAsset}，初始化该组件运行所需的状态和依赖。
     */
    private AptosFungibleAsset() {
    }
    /**
     * 校验 {@code supports} 对应的输入或状态，失败时抛出明确异常。
     */
    static boolean supports(TokenDefinition token) {
        return token != null && STANDARD.equalsIgnoreCase(token.getStandard());
    }
    /**
     * 校验 {@code requireMetadata} 对应的前置条件，不满足时抛出明确异常。
     */
    static String requireMetadata(TokenDefinition token) {
        if (token == null) {
            throw new IllegalArgumentException("Aptos token is required");
        }
        if (!supports(token)) {
            throw new IllegalArgumentException("unsupported Aptos token standard: " + token.getStandard());
        }
        String metadata = token.getContractAddress();
        if (metadata == null || metadata.isBlank()) {
            throw new IllegalArgumentException("Aptos FA metadata address is required");
        }
        return AptosHex.normalizeAddress(metadata);
    }
}
