package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.UtxoRepository;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import com.surprising.wallet.testutil.RuntimeTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

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
        CardanoDepositScanner scanner = new CardanoDepositScanner(
                null,
                new FakeRepository().chainAddressRepository,
                new FakeRepository().depositRecordRepository,
                new FakeRepository().depositService,
                new FakeRepository().chainScanHeightRepository,
                new FakeRepository().chainProfileRepository,
                new FakeRepository().tokenConfigRepository,
                RuntimeTestSupport.enabled());
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
    private static final class FakeRepository extends ChainRepositoryFixture {
        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRepository() {
            super();
            Mockito.doAnswer(invocation -> java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain(invocation.getArgument(0)).network("testnet").build())).when(chainProfileRepository).findAccountByChain(ArgumentMatchers.anyString());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_assetSymbol = invocation.getArgument(1);
                String accountId = "ADA".equals(arg_assetSymbol) ? "ada-account" : "usdc-account";
                return List.of(ChainAddressRecord.builder().chain("ADA").assetSymbol(arg_assetSymbol).accountId(accountId).address("addr").walletRole("DEPOSIT").enabled(true).build());
            }).when(chainAddressRepository).listEnabled(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
        }

    }
}
