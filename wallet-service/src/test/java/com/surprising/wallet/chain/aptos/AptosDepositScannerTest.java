package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.AptosTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code AptosDepositScannerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class AptosDepositScannerTest {
    /**
     * 保存 {@code OWNER}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String OWNER =
            "0x1111111111111111111111111111111111111111111111111111111111111111";
    /**
     * 保存 {@code EXTERNAL}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String EXTERNAL =
            "0x2222222222222222222222222222222222222222222222222222222222222222";
    /**
     * 保存 {@code STORE}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String STORE =
            "0x3333333333333333333333333333333333333333333333333333333333333333";
    /**
     * 保存 {@code METADATA}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String METADATA =
            "0x4444444444444444444444444444444444444444444444444444444444444444";

    /**
     * 验证 {@code creditsFungibleAssetToTheTrackedNativeChainAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void creditsFungibleAssetToTheTrackedNativeChainAddress() throws Exception {
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
        assertEquals("tenant-aptos", repository.creditedAccountId);
    }

    /**
     * 验证 {@code transaction} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code depositAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ChainAddressRecord depositAddress() {
        return ChainAddressRecord.builder()
                .chain("APTOS")
                .assetSymbol("APT")
                .accountId("tenant-aptos")
                .userId(100L)
                .biz(0)
                .addressIndex(1L)
                .address(OWNER)
                .ownerAddress(OWNER)
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    /**
     * 测试替身 {@code ExistingStoreRpc}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class ExistingStoreRpc extends AptosRpcClient {
        /**
         * 保存 {@code transactions}，用于标识测试中的交易、区块或业务记录。
         */
        private final ArrayNode transactions;
        /**
         * 保存 {@code ownerLookups}，用于承载当前测试夹具的配置或运行数据。
         */
        private int ownerLookups;

        /**
         * 验证 {@code ExistingStoreRpc} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private ExistingStoreRpc(JsonNode transaction) {
            super(new ObjectMapper(), "http://aptos.invalid/v1", "");
            transactions = new ObjectMapper().createArrayNode().add(transaction);
        }

        /**
         * 验证 {@code ledgerVersion} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public long ledgerVersion() {
            return 100L;
        }

        /**
         * 验证 {@code transactions} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public JsonNode transactions(long startVersion, int limit) {
            return transactions;
        }

        /**
         * 验证 {@code fungibleStoreOwner} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<String> fungibleStoreOwner(String storeAddress) {
            ownerLookups++;
            return Optional.of(OWNER);
        }
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code token}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final TokenDefinition token = TokenDefinition.builder()
                .chain("APTOS")
                .symbol("USDC")
                .contractAddress(METADATA)
                .decimals(6)
                .standard("APTOS_FA")
                .active(true)
                .build();
        /**
         * 保存 {@code credited}，用于承载当前测试夹具的配置或运行数据。
         */
        private DepositEvent credited;
        /**
         * 保存 {@code creditedAccountId}，用于标识测试中的交易、区块或业务记录。
         */
        private String creditedAccountId;

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeRepository() {
            super(null);
        }

        /**
         * 验证 {@code findProfileByChain} 对应的测试场景，明确输入、预期结果和异常边界。
         */
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

        /**
         * 验证 {@code listTokens} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return List.of(token);
        }

        /**
         * 验证 {@code listChainAddresses} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            return "APT".equals(assetSymbol) ? List.of(depositAddress()) : List.of();
        }

        /**
         * 验证 {@code listChainAddresses} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain) {
            return List.of(depositAddress());
        }

        /**
         * 验证 {@code findScanSafeHeight} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
            return Optional.of(99L);
        }

        /**
         * 验证 {@code recordAptosTransaction} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int recordAptosTransaction(AptosTransactionRecord tx) {
            return 1;
        }

        /**
         * 验证 {@code recordAndCreditDeposit} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean recordAndCreditDeposit(DepositEvent event, long logIndex,
                                              int requiredConfirmations, String accountId) {
            credited = event;
            creditedAccountId = accountId;
            return true;
        }

        /**
         * 验证 {@code updateScanHeight} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        }
    }
}
