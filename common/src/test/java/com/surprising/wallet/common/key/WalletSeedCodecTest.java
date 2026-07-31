package com.surprising.wallet.common.key;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 {@code WalletSeedCodecTest} 覆盖的业务流程、边界条件和异常行为。
 */
class WalletSeedCodecTest {
    /**
     * 验证 {@code acceptsFourDifferentBase64Encoded32ByteSeeds} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void acceptsFourDifferentBase64Encoded32ByteSeeds() {
        WalletKeyConfig config = new WalletKeyConfig(
                seed(1), seed(2), seed(3), seed(4));

        assertDoesNotThrow(() -> WalletSeedCodec.validate(config));
    }

    /**
     * 验证 {@code rejectsWrongLengthAndDuplicateSeeds} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void rejectsWrongLengthAndDuplicateSeeds() {
        assertThrows(IllegalArgumentException.class,
                () -> WalletSeedCodec.decode("sig1Seed", Base64.getEncoder().encodeToString(new byte[31])));
        WalletKeyConfig duplicate = new WalletKeyConfig(
                seed(1), seed(1), seed(3), seed(4));
        assertThrows(IllegalArgumentException.class, () -> WalletSeedCodec.validate(duplicate));
    }

    /**
     * 验证 {@code seed} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String seed(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return Base64.getEncoder().encodeToString(bytes);
    }
}
