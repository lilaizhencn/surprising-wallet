package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.sdk.ed25519.Ed25519Chain;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;
import org.bitcoinj.base.Base58;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code PolkadotAddressGenerationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class PolkadotAddressGenerationTest {
    /**
     * 保存 {@code MASTER_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 验证 {@code derivesStableSs58AddressesFromUnifiedEd25519Tree} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void derivesStableSs58AddressesFromUnifiedEd25519Tree() {
        PolkadotKeyService first = new PolkadotKeyService(MASTER_SEED);
        PolkadotKeyService restarted = new PolkadotKeyService(MASTER_SEED);

        String user0 = first.address(0, 0, 0, 42);
        String user1 = first.address(1, 0, 0, 42);

        assertEquals(user0, restarted.address(0, 0, 0, 42));
        assertNotEquals(user0, user1);
        assertTrue(user0.length() >= 47 && user0.length() <= 49);
        assertEquals(35, Base58.decode(user0).length);
        assertTrue(PolkadotKeyService.isValidSs58Address(user0));
        String corrupted = user0.substring(0, user0.length() - 1)
                + (user0.endsWith("1") ? "2" : "1");
        assertFalse(PolkadotKeyService.isValidSs58Address(corrupted));
        assertEquals("m/44'/354'/0'/0'/0'", first.derive(0).derivationPath());
        assertEquals("m/44'/354'/0'/1'/0'", first.derive(1, 0, 0).derivationPath());
    }

    /**
     * 验证 {@code polkadotPathIsSeparatedFromOtherEd25519Chains} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void polkadotPathIsSeparatedFromOtherEd25519Chains() {
        Ed25519KeyProvider provider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(MASTER_SEED));

        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.POLKADOT, 9).publicKey(),
                provider.derive(Ed25519Chain.SOLANA, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.POLKADOT, 9).publicKey(),
                provider.derive(Ed25519Chain.SUI, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.POLKADOT, 9).publicKey(),
                provider.derive(Ed25519Chain.NEAR, 9).publicKey()));
    }

    /**
     * 验证 {@code keepsDevnetActorAddressesStable} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void keepsDevnetActorAddressesStable() {
        PolkadotKeyService service = new PolkadotKeyService(MASTER_SEED);

        assertEquals("5HiW9iboC5iL7eD95tMffYwwtxnunwNP7UaX5tjDWMQHW1xv",
                service.address(0, 0, 0, 42));
        assertEquals("5G2juBuArdx3uL6QvQEvuMdbu6JE93eoRQE2yw2c9cQijkeW",
                service.address(900_001, 9, 0, 42));
        assertEquals("5GWcyCUmgVc8MRSZMF1CikX3bm5wbzuwe8gxN3LM7vfvdsQe",
                service.address(100_001, 1, 0, 42));
        assertEquals("5DxwtdZkatUKsjMUafscZ1yKLTksRfD74nH3GGBbM8WCEYuB",
                service.address(100_002, 1, 0, 42));
    }
}
