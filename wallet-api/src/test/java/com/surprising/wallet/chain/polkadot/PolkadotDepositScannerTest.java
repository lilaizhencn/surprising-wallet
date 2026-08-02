package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code PolkadotDepositScannerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class PolkadotDepositScannerTest {
    /**
     * 验证 {@code nativeDepositUsesConfiguredAssetDecimals} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void nativeDepositUsesConfiguredAssetDecimals() {
        FakeRuntimeClient runtimeClient = new FakeRuntimeClient();
        runtimeClient.expectedNativeAddresses = List.of("AddrMixed");
        runtimeClient.expectedAssetAddresses = List.of("AddrMixed");
        runtimeClient.nativeTransfers = List.of(new PolkadotRuntimeClient.TransferEvent(
                "native-tx-1", "", "AddrMixed", new BigInteger("9997224699029"),
                95, 3, null, "{}"));
        FakeRepository repository = new FakeRepository();
        repository.address = "AddrMixed";
        PolkadotDepositScanner scanner = new PolkadotDepositScanner(runtimeClient, repository);

        List<DepositEvent> events = scanner.scanAndCredit();

        assertEquals(2, events.size());
        assertEquals("DOT", events.getFirst().assetSymbol());
        assertEquals(new BigDecimal("9.997224699029"), events.getFirst().amount());
        assertEquals(List.of("dot-account", "usdc-account"), repository.creditedAccounts);
    }

    /**
     * 验证 {@code assetHubDepositCreditsTokenAccountWhenAddressMatchesNativeAccount} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void assetHubDepositCreditsTokenAccountWhenAddressMatchesNativeAccount() {
        FakeRuntimeClient runtimeClient = new FakeRuntimeClient();
        FakeRepository repository = new FakeRepository();
        PolkadotDepositScanner scanner = new PolkadotDepositScanner(runtimeClient, repository);

        List<DepositEvent> events = scanner.scanAndCredit();

        assertEquals(1, events.size());
        assertEquals("USDC", events.getFirst().assetSymbol());
        assertEquals(List.of("usdc-account"), repository.creditedAccounts);
    }

    /**
     * 测试替身 {@code FakeRuntimeClient}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRuntimeClient extends PolkadotRuntimeClient {
        /**
         * 保存 {@code nativeTransfers}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private List<TransferEvent> nativeTransfers = List.of();
        /**
         * 保存 {@code expectedNativeAddresses}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private List<String> expectedNativeAddresses = List.of("addr");
        /**
         * 保存 {@code expectedAssetAddresses}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private List<String> expectedAssetAddresses = List.of("addr");

        /**
         * 验证 {@code FakeRuntimeClient} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRuntimeClient() {
            super(null, null);
        }

        /**
         * 验证 {@code latestFinalizedHeight} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public long latestFinalizedHeight() {
            return 100;
        }

        /**
         * 验证 {@code latestAssetHubFinalizedHeight} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public long latestAssetHubFinalizedHeight() {
            return 200;
        }

        /**
         * 验证 {@code scanNativeTransfers} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<TransferEvent> scanNativeTransfers(long fromBlock, long toBlock,
                                                       Collection<String> addresses) {
            assertEquals(expectedNativeAddresses, addresses);
            return nativeTransfers;
        }

        /**
         * 验证 {@code scanAssetTransfers} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<TransferEvent> scanAssetTransfers(long fromBlock, long toBlock,
                                                      Collection<String> addresses,
                                                      Map<String, TokenDefinition> tokensByAssetId) {
            assertEquals(expectedAssetAddresses, addresses);
            return List.of(new TransferEvent("asset-tx-1", "sender", expectedAssetAddresses.getFirst(),
                    new BigInteger("2500000"), 195, 7, "1984", "{}"));
        }
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code creditedAccounts}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<String> creditedAccounts = new ArrayList<>();
        /**
         * 保存 {@code address}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private String address = "addr";

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRepository() {
            super(null);
        }

        /**
         * 验证 {@code findProfileByChain} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            return Optional.of(AccountChainProfile.builder()
                    .chain("DOT")
                    .network("westend")
                    .depositConfirmations(1)
                    .scanBatchSize(10)
                    .build());
        }

        /**
         * 验证 {@code findScanSafeHeight} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
            return Optional.empty();
        }

        /**
         * 验证 {@code updateScanHeight} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        }

        /**
         * 验证 {@code listTokens} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return List.of(TokenDefinition.builder()
                    .chain("DOT")
                    .symbol("USDC")
                    .contractAddress("1984")
                    .decimals(6)
                    .active(true)
                    .build());
        }

        /**
         * 验证 {@code findAsset} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.of(ChainAsset.builder()
                    .chain("DOT")
                    .symbol("DOT")
                    .decimals(12)
                    .active(true)
                    .build());
        }

        /**
         * 验证 {@code listChainAddresses} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            String accountId = "DOT".equals(assetSymbol) ? "dot-account" : "usdc-account";
            return List.of(ChainAddressRecord.builder()
                    .chain("DOT")
                    .assetSymbol(assetSymbol)
                    .accountId(accountId)
                    .address(address)
                    .walletRole("DEPOSIT")
                    .enabled(true)
                    .build());
        }

        /**
         * 验证 {@code recordAndCreditDeposit} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean recordAndCreditDeposit(DepositEvent event, long logIndex,
                                              int requiredConfirmations, String accountId) {
            creditedAccounts.add(accountId);
            return true;
        }
    }
}
