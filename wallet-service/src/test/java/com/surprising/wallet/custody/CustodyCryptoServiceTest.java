package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.service.CustodyCryptoService;
import com.surprising.wallet.custody.model.CustodySecurityProperties;

class CustodyCryptoServiceTest {
    @Test
    void encryptsWithRandomNonceAndAuthenticatesCiphertext() {
        CustodySecurityProperties properties = properties();
        CustodyCryptoService crypto = new CustodyCryptoService(properties);

        String first = crypto.encrypt("tenant-secret");
        String second = crypto.encrypt("tenant-secret");

        assertNotEquals(first, second);
        assertEquals("tenant-secret", crypto.decrypt(first));
        assertEquals("tenant-secret", crypto.decrypt(second));
        byte[] payload = Base64.getUrlDecoder().decode(first.substring("v1:".length()));
        payload[payload.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void hmacComparisonIsExact() {
        CustodyCryptoService crypto = new CustodyCryptoService(properties());
        String signature = crypto.hmacSha256("secret", "canonical-request");

        assertTrue(crypto.constantTimeEquals(signature, signature));
        assertFalse(crypto.constantTimeEquals(signature, signature + "x"));
    }

    @Test
    void refusesMissingOrShortMasterKey() {
        CustodySecurityProperties missing = new CustodySecurityProperties();
        CustodyCryptoService crypto = new CustodyCryptoService(missing);
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));

        missing.setSecretMasterKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));
    }

    private static CustodySecurityProperties properties() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        CustodySecurityProperties properties = new CustodySecurityProperties();
        properties.setSecretMasterKey(Base64.getEncoder().encodeToString(key));
        return properties;
    }
}
