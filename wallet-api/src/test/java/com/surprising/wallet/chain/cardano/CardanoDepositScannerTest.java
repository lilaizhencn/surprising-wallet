package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code CardanoDepositScannerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CardanoDepositScannerTest {
    /**
     * 保存 {@code TOKEN_UNIT}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String TOKEN_UNIT =
            "00000000000000000000000000000000000000000000000000000000" + "55534443";

    /**
     * 验证 {@code tracksNativeAndTokenAccountsSeparatelyForSameAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tracksNativeAndTokenAccountsSeparatelyForSameAddress() {
        CardanoDepositScanner scanner = new CardanoDepositScanner(null, new FakeRepository());
        TokenDefinition token = TokenDefinition.builder()
                .chain("ADA")
                .symbol("USDC")
                .contractAddress(TOKEN_UNIT)
                .decimals(6)
                .active(true)
                .build();

        Map<String, CardanoDepositScanner.TrackedCardanoAddress> addresses =
                scanner.trackedDepositAddresses(Map.of(CardanoAssetUnit.fromTokenContract(TOKEN_UNIT), token));

        CardanoDepositScanner.TrackedCardanoAddress tracked = addresses.get("addr");
        assertEquals("ada-account", tracked.nativeRecord().getAccountId());
        assertEquals("usdc-account", tracked.tokenRecordsByUnit().get(TOKEN_UNIT).getAccountId());
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRepository() {
            super(null);
        }

        /**
         * 验证 {@code listChainAddresses} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            String accountId = "ADA".equals(assetSymbol) ? "ada-account" : "usdc-account";
            return List.of(ChainAddressRecord.builder()
                    .chain("ADA")
                    .assetSymbol(assetSymbol)
                    .accountId(accountId)
                    .address("addr")
                    .walletRole("DEPOSIT")
                    .enabled(true)
                    .build());
        }
    }
}
