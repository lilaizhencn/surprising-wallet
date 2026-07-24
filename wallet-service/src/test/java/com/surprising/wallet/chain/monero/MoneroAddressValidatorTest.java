package com.surprising.wallet.chain.monero;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneroAddressValidatorTest {
    private static final String GETMONERO_DONATION_ADDRESS =
            "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H";

    @Test
    void validatesMoneroBase58AddressChecksum() {
        assertTrue(MoneroAddressValidator.isValid(GETMONERO_DONATION_ADDRESS));
        assertFalse(MoneroAddressValidator.isValid(
                GETMONERO_DONATION_ADDRESS.substring(0, GETMONERO_DONATION_ADDRESS.length() - 1) + "J"));
        assertFalse(MoneroAddressValidator.isValid("8".repeat(95)));
        assertFalse(MoneroAddressValidator.isValid("not-an-address"));
    }
}
