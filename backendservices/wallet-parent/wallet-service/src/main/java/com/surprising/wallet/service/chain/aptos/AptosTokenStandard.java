package com.surprising.wallet.service.chain.aptos;

import com.surprising.wallet.common.chain.TokenDefinition;

import java.util.Locale;

enum AptosTokenStandard {
    COIN,
    FUNGIBLE_ASSET;

    static AptosTokenStandard from(TokenDefinition token) {
        if (token == null) {
            throw new IllegalArgumentException("Aptos token is required");
        }
        String standard = token.getStandard() == null
                ? ""
                : token.getStandard().trim().toUpperCase(Locale.ROOT);
        return switch (standard) {
            case "APTOS_COIN" -> COIN;
            case "APTOS_FA" -> FUNGIBLE_ASSET;
            default -> throw new IllegalArgumentException("unsupported Aptos token standard: " + standard);
        };
    }
}
