package com.surprising.wallet.chain.ton;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TonCenterClientTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TonCenterClientTest {

    /**
     * 验证 {@code comparesTonMessageHashesAcrossHexBase64AndBase64Url} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
