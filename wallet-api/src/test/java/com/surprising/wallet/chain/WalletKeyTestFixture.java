package com.surprising.wallet.chain;

import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;

import java.util.Arrays;
import java.util.Base64;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试辅助类 {@code WalletKeyTestFixture}，为相关测试提供隔离环境或共享数据。
 */
public final class WalletKeyTestFixture {
    /**
     * 验证 {@code WalletKeyTestFixture} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private WalletKeyTestFixture() {
    }

    /**
     * 验证 {@code provider} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    public static WalletKeyMaterialProvider provider() {
        return new WalletKeyMaterialProvider(
                new WalletKeyConfig(seed(0x11), seed(0x22), seed(0x33), seed(0x44)),
                WalletKeyMaterialProvider.Mode.WALLET_SERVER);
    }

    /** Use the same key-material boundary while preserving each test's derivation vectors. */
    public static WalletKeyMaterialProvider provider(String ed25519Seed) {
        var material = mock(WalletKeyMaterialProvider.class);
        when(material.isConfigured()).thenReturn(true);
        when(material.ed25519()).thenReturn(new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(ed25519Seed)));
        return material;
    }

    /**
     * 验证 {@code seed} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String seed(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return Base64.getEncoder().encodeToString(value);
    }
}
