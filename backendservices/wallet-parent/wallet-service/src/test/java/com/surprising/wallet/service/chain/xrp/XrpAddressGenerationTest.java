package com.surprising.wallet.service.chain.xrp;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.xrpl.xrpl4j.model.transactions.Address;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrpAddressGenerationTest {
    private static final BigInteger FIRST_PRIVATE_KEY =
            new BigInteger("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16);
    private static final BigInteger SECOND_PRIVATE_KEY =
            new BigInteger("2234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16);

    @Test
    void derivesStableClassicAddressFromSecp256k1Key() {
        ECKey firstKey = ECKey.fromPrivate(FIRST_PRIVATE_KEY);
        ECKey secondKey = ECKey.fromPrivate(SECOND_PRIVATE_KEY);

        String first = XrpKeyService.address(firstKey);
        String restarted = XrpKeyService.address(ECKey.fromPrivate(FIRST_PRIVATE_KEY));
        String second = XrpKeyService.address(secondKey);

        assertEquals(first, restarted);
        assertNotEquals(first, second);
        assertTrue(first.startsWith("r"));
        Address.of(first).validateAddress();
        Address.of(second).validateAddress();
    }
}
