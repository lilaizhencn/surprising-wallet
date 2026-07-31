package com.surprising.wallet.sdk.ed25519;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code Ed25519KeyProviderTest} 覆盖的业务流程、边界条件和异常行为。
 */
class Ed25519KeyProviderTest {
    /**
     * 保存 {@code MASTER_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final byte[] MASTER_SEED = HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f"
                    + "101112131415161718191a1b1c1d1e1f");

    /**
     * 验证 {@code derivesStableChainSeparatedAndUserSeparatedKeys} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void derivesStableChainSeparatedAndUserSeparatedKeys() {
        Ed25519KeyProvider first = new Ed25519KeyProvider(MASTER_SEED);
        Ed25519KeyProvider restarted = new Ed25519KeyProvider(MASTER_SEED);

        Ed25519DerivedKey solUser0 = first.derive(Ed25519Chain.SOLANA, 0);
        Ed25519DerivedKey solUser1 = first.derive(Ed25519Chain.SOLANA, 1);
        Ed25519DerivedKey tonUser0 = first.derive(Ed25519Chain.TON, 0);

        assertArrayEquals(solUser0.publicKey(),
                restarted.derive(Ed25519Chain.SOLANA, 0).publicKey());
        assertFalse(Arrays.equals(solUser0.publicKey(), solUser1.publicKey()));
        assertFalse(Arrays.equals(solUser0.publicKey(), tonUser0.publicKey()));
        assertNotEquals(solUser0.derivationPath(), tonUser0.derivationPath());
        assertTrue(solUser0.derivationPath().startsWith("m/44'/501'"));
        assertTrue(tonUser0.derivationPath().startsWith("m/44'/607'"));
    }

    /**
     * 验证 {@code signsAndVerifiesWithoutSecp256k1Conversion} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void signsAndVerifiesWithoutSecp256k1Conversion() {
        Ed25519KeyProvider provider = new Ed25519KeyProvider(MASTER_SEED);
        byte[] message = "wallet-ed25519-tree".getBytes(StandardCharsets.UTF_8);
        Ed25519DerivedKey key = provider.derive(Ed25519Chain.APTOS, 7);
        byte[] signature = provider.sign(Ed25519Chain.APTOS, 7, message);

        assertTrue(provider.verify(key.publicKey(), message, signature));
        assertFalse(provider.verify(key.publicKey(),
                "different".getBytes(StandardCharsets.UTF_8), signature));
    }
}
