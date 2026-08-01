package com.surprising.wallet.chain.doge;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import org.bitcoinj.base.LegacyAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code DogecoinAddressGenerationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class DogecoinAddressGenerationTest {
    /**
     * 保存 {@code XPUB_1}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_1 =
            "tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB";
    /**
     * 保存 {@code XPUB_2}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_2 =
            "tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT";
    /**
     * 保存 {@code XPUB_3}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_3 =
            "tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4";

    /**
     * 验证 {@code shouldGenerateDeterministicDogecoinTestnetP2shAddresses} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void shouldGenerateDeterministicDogecoinTestnetP2shAddresses() {
        String deposit = addressAt(9101, 1, 0);
        String collection = addressAt(9102, 1, 0);
        String hot = addressAt(0, 0, 0);

        assertEquals(196, LegacyAddress.fromBase58(
                DogecoinNetworkParameters.testnet(), deposit).getVersion());
        assertNotEquals(deposit, collection);
        assertNotEquals(deposit, hot);
        System.out.println("DOGE_TESTNET_DEPOSIT_ADDRESS=" + deposit);
        System.out.println("DOGE_TESTNET_COLLECTION_ADDRESS=" + collection);
        System.out.println("DOGE_TESTNET_HOT_ADDRESS=" + hot);
    }

    /**
     * 验证 {@code addressAt} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String addressAt(int userId, int biz, int index) {
        LegacyMultiSignAddressGenerator generator = new LegacyMultiSignAddressGenerator();
        generator.addECKey(derive(XPUB_1, userId, biz, index).getEcKey());
        generator.addECKey(derive(XPUB_2, userId, biz, index).getEcKey());
        generator.addECKey(derive(XPUB_3, userId, biz, index).getEcKey());
        return generator.generateAddress(DogecoinNetworkParameters.testnet(), 2);
    }

    /**
     * 验证 {@code derive} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Bip32Node derive(String xpub, int userId, int biz, int index) {
        return Bip32Node.decode(xpub)
                .getChild(44)
                .getChild(3)
                .getChild(biz)
                .getChild(userId)
                .getChild(index);
    }
}
