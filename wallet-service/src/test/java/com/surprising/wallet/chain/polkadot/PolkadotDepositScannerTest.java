package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolkadotDepositScannerTest {
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

    private static final class FakeRuntimeClient extends PolkadotRuntimeClient {
        private List<TransferEvent> nativeTransfers = List.of();
        private List<String> expectedNativeAddresses = List.of("addr");
        private List<String> expectedAssetAddresses = List.of("addr");

        FakeRuntimeClient() {
            super(null, null);
        }

        @Override
        public long latestFinalizedHeight() {
            return 100;
        }

        @Override
        public long latestAssetHubFinalizedHeight() {
            return 200;
        }

        @Override
        public List<TransferEvent> scanNativeTransfers(long fromBlock, long toBlock,
                                                       Collection<String> addresses) {
            assertEquals(expectedNativeAddresses, addresses);
            return nativeTransfers;
        }

        @Override
        public List<TransferEvent> scanAssetTransfers(long fromBlock, long toBlock,
                                                      Collection<String> addresses,
                                                      Map<String, TokenDefinition> tokensByAssetId) {
            assertEquals(expectedAssetAddresses, addresses);
            return List.of(new TransferEvent("asset-tx-1", "sender", expectedAssetAddresses.getFirst(),
                    new BigInteger("2500000"), 195, 7, "1984", "{}"));
        }
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final List<String> creditedAccounts = new ArrayList<>();
        private String address = "addr";

        FakeRepository() {
            super(null);
        }

        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            return Optional.of(AccountChainProfile.builder()
                    .chain("DOT")
                    .network("westend")
                    .depositConfirmations(1)
                    .scanBatchSize(10)
                    .build());
        }

        @Override
        public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
            return Optional.empty();
        }

        @Override
        public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        }

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

        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.of(ChainAsset.builder()
                    .chain("DOT")
                    .symbol("DOT")
                    .decimals(12)
                    .active(true)
                    .build());
        }

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

        @Override
        public boolean recordAndCreditDeposit(DepositEvent event, long logIndex,
                                              int requiredConfirmations, String accountId) {
            creditedAccounts.add(accountId);
            return true;
        }
    }
}
