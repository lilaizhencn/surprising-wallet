package com.surprising.wallet.chain.ton;

import com.surprising.wallet.sdk.ed25519.Ed25519Chain;
import com.surprising.wallet.sdk.ed25519.Ed25519KeyProvider;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.address.Address;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TonAddressGenerationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TonAddressGenerationTest {
    /**
     * 保存 {@code MASTER_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 验证 {@code derivesStableWalletV4R2TestnetAddressesFromUnifiedEd25519Tree} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void derivesStableWalletV4R2TestnetAddressesFromUnifiedEd25519Tree() {
        TonKeyService first = new TonKeyService(MASTER_SEED);
        TonKeyService restarted = new TonKeyService(MASTER_SEED);

        String user0 = friendly(first, 0);
        String user1 = friendly(first, 1);
        String restartedUser0 = friendly(restarted, 0);

        assertEquals(user0, restartedUser0);
        assertNotEquals(user0, user1);
        assertTrue(Address.isValid(user0));
        assertTrue(Address.of(user0).isTestOnly());
        assertEquals("m/44'/607'/0'/0'", first.derive(0).derivationPath());
        assertEquals("m/44'/607'/1'/0'", first.derive(1).derivationPath());
    }

    /**
     * 验证 {@code tonPathIsSeparateFromSolanaAptosAndSuiPaths} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tonPathIsSeparateFromSolanaAptosAndSuiPaths() {
        Ed25519KeyProvider provider = new Ed25519KeyProvider(
                Ed25519KeyProvider.decodeMasterSeed(MASTER_SEED));

        assertEquals("m/44'/607'/9'/0'", provider.derive(Ed25519Chain.TON, 9).derivationPath());
        assertNotEquals(0, Arrays.compareUnsigned(
                provider.derive(Ed25519Chain.SOLANA, 9).publicKey(),
                provider.derive(Ed25519Chain.TON, 9).publicKey()));
        assertNotEquals(0, Arrays.compareUnsigned(
                provider.derive(Ed25519Chain.APTOS, 9).publicKey(),
                provider.derive(Ed25519Chain.TON, 9).publicKey()));
        assertNotEquals(0, Arrays.compareUnsigned(
                provider.derive(Ed25519Chain.SUI, 9).publicKey(),
                provider.derive(Ed25519Chain.TON, 9).publicKey()));
    }

    /**
     * 验证 {@code friendly} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String friendly(TonKeyService keys, long index) {
        return keys.wallet(index).getAddress().toString(true, true, false, true);
    }
}
