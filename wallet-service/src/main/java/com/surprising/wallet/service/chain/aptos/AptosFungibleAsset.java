package com.surprising.wallet.service.chain.aptos;

import com.surprising.wallet.common.chain.TokenDefinition;
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
