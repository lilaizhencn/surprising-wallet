package com.surprising.wallet.chain.sui;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 验证 {@code SuiSigningTest} 覆盖的业务流程、边界条件和异常行为。
 */
class SuiSigningTest {
    /**
     * 保存 {@code MASTER_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 验证 {@code buildsSuiSerializedSignatureFromTxBytes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void buildsSuiSerializedSignatureFromTxBytes() {
        SuiKeyService keys = new SuiKeyService(MASTER_SEED);
        SuiTransactionSigner signer = new SuiTransactionSigner(keys);
        String txBytes = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5});

        byte[] signature = Base64.getDecoder().decode(signer.signTransactionBytes(3, txBytes));

        assertEquals(97, signature.length);
        assertEquals(0, signature[0]);
    }

    /**
     * 验证 {@code signaturesCommitToTransactionBytes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void signaturesCommitToTransactionBytes() {
        SuiKeyService keys = new SuiKeyService(MASTER_SEED);
        SuiTransactionSigner signer = new SuiTransactionSigner(keys);

        String first = signer.signTransactionBytes(3, Base64.getEncoder().encodeToString(new byte[]{1}));
        String second = signer.signTransactionBytes(3, Base64.getEncoder().encodeToString(new byte[]{2}));

        assertNotEquals(first, second);
    }
}
