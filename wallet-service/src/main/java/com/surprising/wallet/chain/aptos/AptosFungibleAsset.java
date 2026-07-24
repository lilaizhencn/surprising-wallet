package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.TokenDefinition;
/**
 * Aptos Fungible Asset (FA) 标准工具类。
 *
 * <p>Aptos FA 是 Move 生态中 fungible token 的统一标准，取代旧的 Coin 标准。
 * 提供 FA 代币的 metadata 地址提取和标准校验。
 */
final class AptosFungibleAsset {
    static final String STANDARD = "APTOS_FA";
    private AptosFungibleAsset() {
    }
    static boolean supports(TokenDefinition token) {
        return token != null && STANDARD.equalsIgnoreCase(token.getStandard());
    }
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
