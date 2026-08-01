package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.model.CustodySecurityProperties;

/**
 * 验证 {@code CustodyCryptoServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyCryptoServiceTest {
    /**
     * 验证 {@code encryptsWithRandomNonceAndAuthenticatesCiphertext} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code hmacComparisonIsExact} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void hmacComparisonIsExact() {
        CustodyCryptoService crypto = new CustodyCryptoService(properties());
        String signature = crypto.hmacSha256("secret", "canonical-request");

        assertTrue(crypto.constantTimeEquals(signature, signature));
        assertFalse(crypto.constantTimeEquals(signature, signature + "x"));
    }

    /**
     * 验证 {@code refusesMissingOrShortMasterKey} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void refusesMissingOrShortMasterKey() {
        CustodySecurityProperties missing = new CustodySecurityProperties();
        CustodyCryptoService crypto = new CustodyCryptoService(missing);
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));

        missing.setSecretMasterKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));
    }

    /**
     * 验证 {@code properties} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
