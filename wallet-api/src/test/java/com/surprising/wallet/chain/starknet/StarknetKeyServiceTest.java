package com.surprising.wallet.chain.starknet;

import com.surprising.wallet.chain.WalletKeyTestFixture;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Starknet counterfactual 地址的确定性和输入隔离。 */
class StarknetKeyServiceTest {
    /** 本地 Starknet devnet 默认 OpenZeppelin 账户 class hash，仅用于地址算法测试。 */
    private static final String DEVNET_ACCOUNT_CLASS_HASH =
            "0x05b4b537eaa2399e3aa99c4e2e0208ebd6c71bc1467938cd52c798c601e43564";

    /** 验证相同租户坐标始终派生相同地址。 */
    @Test
    void derivesDeterministicCounterfactualAddress() {
        StarknetKeyService service = new StarknetKeyService(WalletKeyTestFixture.provider());
        AccountChainProfile profile = profile();

        StarknetKeyService.DerivedKey first = service.derive(profile, 100L, 1, 0L);
        StarknetKeyService.DerivedKey same = service.derive(profile, 100L, 1, 0L);
        StarknetKeyService.DerivedKey next = service.derive(profile, 101L, 1, 0L);
        ChainAddressRecord record = first.toAddressRecord(profile, 100L, 1, 0L, "DEPOSIT");

        assertEquals(first.address(), same.address());
        assertNotEquals(first.address(), next.address());
        assertTrue(StarknetKeyService.isValidAddress(first.address().hexString()));
        assertEquals(first.address().hexString().toLowerCase(), record.getAddress());
        assertEquals(record.getAddress(), record.getOwnerAddress());
    }

    /** 验证地址文本标准化和非法 felt 被拒绝。 */
    @Test
    void validatesAndNormalizesAddress() {
        String address = "0x01";
        assertTrue(StarknetKeyService.isValidAddress(address));
        assertEquals("0x1", StarknetKeyService.normalizeAddress(address));
        assertTrue(!StarknetKeyService.isValidAddress(
                "0x800000000000011000000000000000000000000000000000000000000000000"));
    }

    /** 返回地址测试使用的 Starknet Sepolia 配置。 */
    private static AccountChainProfile profile() {
        return AccountChainProfile.builder()
                .chain("STARKNET")
                .network("sepolia")
                .family("starknet")
                .bip44CoinType(9004)
                .nativeSymbol("STRK")
                .accountClassHash(DEVNET_ACCOUNT_CLASS_HASH)
                .build();
    }
}
