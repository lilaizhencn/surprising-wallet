package com.surprising.wallet.service.chain.ton;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TonCenterClientTest {

    @Test
    void comparesTonMessageHashesAcrossHexBase64AndBase64Url() {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (i * 7 + 3);
        }
        String hex = HexFormat.of().formatHex(hash);
        String base64 = Base64.getEncoder().encodeToString(hash);
        String base64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        assertTrue(TonCenterClient.sameHash(hex, base64));
        assertTrue(TonCenterClient.sameHash(base64Url, base64));
        assertFalse(TonCenterClient.sameHash(hex, Base64.getEncoder().encodeToString(new byte[32])));
        assertFalse(TonCenterClient.sameHash("not-a-hash", base64));
    }
}
