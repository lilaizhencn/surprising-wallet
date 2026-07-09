package com.surprising.wallet.service.chain.cardano;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardanoAssetUnitTest {
    private static final String POLICY = "0123456789abcdef0123456789abcdef0123456789abcdef01234567";
    private static final String ASSET = "55534443";

    @Test
    void tokenContractAcceptsPolicyDotAssetNameHex() {
        String unit = CardanoAssetUnit.fromTokenContract(POLICY + "." + ASSET);

        assertEquals(POLICY + ASSET.toLowerCase(), unit);
        assertEquals(POLICY, CardanoAssetUnit.policyId(unit));
        assertEquals(ASSET.toLowerCase(), CardanoAssetUnit.assetNameHex(unit));
    }

    @Test
    void tokenContractAcceptsBlockfrostUnit() {
        String unit = CardanoAssetUnit.fromTokenContract(POLICY + ASSET);

        assertEquals(POLICY + ASSET.toLowerCase(), unit);
    }

    @Test
    void tokenContractRejectsOddHex() {
        assertThrows(IllegalArgumentException.class,
                () -> CardanoAssetUnit.fromTokenContract(POLICY + ".abc"));
    }

    @Test
    void depositLogIndexSeparatesMultiAssetOutput() {
        assertEquals(30_004L, CardanoAssetUnit.depositLogIndex(3, 4));
    }
}
