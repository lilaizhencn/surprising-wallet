package com.surprising.wallet.service.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AptosDepositScannerTest {
    private static final String OWNER =
            "0x1111111111111111111111111111111111111111111111111111111111111111";
    private static final String EXTERNAL =
            "0x2222222222222222222222222222222222222222222222222222222222222222";
    private static final String STORE =
            "0x3333333333333333333333333333333333333333333333333333333333333333";
    private static final String METADATA =
            "0x4444444444444444444444444444444444444444444444444444444444444444";

    @Test
    void resolvesExistingPrimaryStoreOwnerFromRpc() throws Exception {
        FakeRepository repository = new FakeRepository();
        ExistingStoreRpc rpc = new ExistingStoreRpc(transaction());
        AptosDepositScanner scanner = new AptosDepositScanner(rpc, repository);

        List<DepositEvent> events = scanner.scanAndCredit();

        assertEquals(1, events.size());
        assertEquals("USDC", events.get(0).assetSymbol());
        assertEquals(new BigDecimal("1.250000"), events.get(0).amount());
        assertEquals(OWNER, events.get(0).toAddress());
        assertEquals(METADATA, events.get(0).tokenAddress());
        assertEquals(1, rpc.ownerLookups);
        assertEquals(events.get(0), repository.credited);
        assertEquals("tenant-usdc", repository.creditedAccountId);
    }

    private static JsonNode transaction() throws Exception {
        return new ObjectMapper().readTree("""
                {
                  "type": "user_transaction",
                  "success": true,
                  "sender": "%s",
                  "version": "100",
                  "hash": "0xdeposit",
                  "gas_used": "10",
                  "gas_unit_price": "100",
                  "sequence_number": "7",
                  "changes": [
                    {
                      "type": "write_resource",
                      "address": "%s",
                      "data": {
                        "type": "0x1::fungible_asset::FungibleStore",
                        "data": {"metadata": {"inner": "%s"}, "balance": "1250000"}
                      }
                    }
                  ],
                  "events": [
                    {
                      "type": "0x1::fungible_asset::Deposit",
                      "data": {"store": "%s", "amount": "1250000"}
                    }
                  ]
                }
                """.formatted(EXTERNAL, STORE, METADATA, STORE));
    }

    private static ChainAddressRecord depositAddress() {
        return ChainAddressRecord.builder()
                .chain("APTOS")
                .assetSymbol("USDC")
                .accountId("tenant-usdc")
                .userId(100L)
                .biz(0)
                .addressIndex(1L)
                .address(OWNER)
                .ownerAddress(OWNER)
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    private static final class ExistingStoreRpc extends AptosRpcClient {
        private final ArrayNode transactions;
        private int ownerLookups;

        private ExistingStoreRpc(JsonNode transaction) {
            super(new ObjectMapper(), "http://aptos.invalid/v1", "");
            transactions = new ObjectMapper().createArrayNode().add(transaction);
        }

        @Override
        public long ledgerVersion() {
            return 100L;
        }

        @Override
        public JsonNode transactions(long startVersion, int limit) {
            return transactions;
        }

        @Override
        public Optional<String> fungibleStoreOwner(String storeAddress) {
            ownerLookups++;
            return Optional.of(OWNER);
        }
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final TokenDefinition token = TokenDefinition.builder()
                .chain("APTOS")
                .symbol("USDC")
                .contractAddress(METADATA)
                .decimals(6)
                .standard("APTOS_FA")
                .active(true)
                .build();
        private DepositEvent credited;
        private String creditedAccountId;

        private FakeRepository() {
            super(null);
        }

        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            return Optional.of(AccountChainProfile.builder()
                    .chain("APTOS")
                    .network("testnet")
                    .family("aptos")
                    .nativeSymbol("APT")
                    .depositConfirmations(1)
                    .scanBatchSize(100)
                    .enabled(true)
                    .build());
        }

        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return List.of(token);
        }

        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            return "USDC".equals(assetSymbol) ? List.of(depositAddress()) : List.of();
        }

        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain) {
            return List.of(depositAddress());
        }

        @Override
        public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
            return Optional.of(99L);
        }

        @Override
        public int recordAptosTransaction(AptosTransactionRecord tx) {
            return 1;
        }

        @Override
        public boolean recordAndCreditDeposit(DepositEvent event, long logIndex,
                                              int requiredConfirmations, String accountId) {
            credited = event;
            creditedAccountId = accountId;
            return true;
        }

        @Override
        public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        }
    }
}
