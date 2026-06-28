package com.surprising.wallet.service.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolkadotDepositScannerTest {
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
            return List.of();
        }

        @Override
        public List<TransferEvent> scanAssetTransfers(long fromBlock, long toBlock,
                                                      Collection<String> addresses,
                                                      Map<String, TokenDefinition> tokensByAssetId) {
            assertEquals(List.of("addr"), addresses);
            return List.of(new TransferEvent("asset-tx-1", "sender", "addr",
                    new BigInteger("2500000"), 195, 7, "1984", "{}"));
        }
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final List<String> creditedAccounts = new ArrayList<>();

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
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            String accountId = "DOT".equals(assetSymbol) ? "dot-account" : "usdc-account";
            return List.of(ChainAddressRecord.builder()
                    .chain("DOT")
                    .assetSymbol(assetSymbol)
                    .accountId(accountId)
                    .address("addr")
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
