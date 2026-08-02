package com.surprising.wallet.chain.starknet;

import com.surprising.wallet.chain.WalletKeyTestFixture;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.StarknetTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用本地 Starknet Devnet 验证账户、原生币、Token、扫描和归集的完整链路。 */
class StarknetDevnetFullFlowIntegrationTest {
    /** 本地 Devnet 默认 STRK 合约地址。 */
    private static final String STRK =
            "0x04718f5a0fc34cc1af16a1cdee98ffb20c31f5cd61d6ab07201858f4287c938d";
    /** 本地 Devnet 默认 ETH ERC-20 合约地址，用作真实 Token 分支测试资产。 */
    private static final String ETH =
            "0x049d36570d4e46f48e99674bd3fcc84644ddd6b96f7c741b1562b82f9e004dc7";
    /** Devnet v0.8.2 默认 OpenZeppelin 账户 class hash。 */
    private static final String ACCOUNT_CLASS_HASH =
            "0x05b4b537eaa2399e3aa99c4e2e0208ebd6c71bc1467938cd52c798c601e43564";
    /** Devnet seed=0 时的第一个已部署账户，用作外部收款地址。 */
    private static final String EXTERNAL_ADDRESS =
            "0x64b48806902a367c8598f4f95c305e8c1a1acba5f082d294a43793113115691";
    /** 本地 Devnet JSON-RPC 地址。 */
    private static final String DEFAULT_RPC = "http://127.0.0.1:5050";

    /** 验证 Starknet 账户部署、原生币和 Token 转账、充值扫描、提现确认和归集确认。 */
    @Test
    void shouldExecuteStarknetDevnetFullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("starknet.devnet.flow.enabled"),
                "set -Dstarknet.devnet.flow.enabled=true to run the local Starknet Devnet flow");

        String rpcUrl = System.getProperty("starknet.devnet.rpc", DEFAULT_RPC);
        ping(rpcUrl);
        AccountChainProfile profile = profile();
        WalletKeyMaterialProvider keyMaterial = WalletKeyTestFixture.provider();
        StarknetKeyService keys = new StarknetKeyService(keyMaterial);
        ChainRpcNodeService nodes = new DevnetRpcNodeService(rpcUrl + "/rpc");
        StarknetRpcClient rpc = new StarknetRpcClient(nodes);
        InMemoryRepository repository = new InMemoryRepository();
        StarknetTransactionService transactions = new StarknetTransactionService(
                rpc, keys, repository, null);

        ChainAddressRecord source = address(keys, profile, 900_001L, 9, 0L, "EXTERNAL");
        ChainAddressRecord deposit = address(keys, profile, 100_001L, 1, 0L, "DEPOSIT");
        ChainAddressRecord hot = address(keys, profile, 0L, 0, 0L, "DEPOSIT");
        repository.addresses.add(deposit);
        UUID tenantId = UUID.randomUUID();

        mint(rpcUrl, source.getAddress(), "300000000000000000000", "FRI");
        mint(rpcUrl, source.getAddress(), "100000000000000000000", "WEI");

        String withdrawalHash = transactions.sendNative(profile, source, EXTERNAL_ADDRESS,
                new BigDecimal("1"));
        repository.withdrawalHash = withdrawalHash;
        assertTrue(transactions.confirmWithdrawal(tenantId, profile, "STARKNET-WD-1",
                "STRK", source.getAccountId(), new BigDecimal("1")));

        String nativeDepositHash = transactions.sendNative(profile, source, deposit.getAddress(),
                new BigDecimal("10"));
        TokenDefinition token = TokenDefinition.builder()
                .chain("STARKNET")
                .symbol("ETH_DEVNET")
                .contractAddress(ETH)
                .decimals(18)
                .standard("ERC20")
                .active(true)
                .build();
        repository.token = token;
        String tokenDepositHash = transactions.sendToken(profile, source, token, deposit.getAddress(),
                new BigDecimal("2"));

        StarknetDepositScanner scanner = new StarknetDepositScanner(rpc, repository);
        List<DepositEvent> deposits = scanner.scanAndCredit(profile);
        assertTrue(deposits.stream().anyMatch(event -> nativeDepositHash.equals(event.txId())));
        assertTrue(deposits.stream().anyMatch(event -> tokenDepositHash.equals(event.txId())));

        String nativeCollectionHash = transactions.collectNative(tenantId,
                "STARKNET-COLLECT-STRK", profile, deposit, hot.getAddress(), new BigDecimal("1"));
        assertTrue(transactions.confirmCollection(tenantId, profile,
                "STARKNET-COLLECT-STRK", "STRK"));
        String tokenCollectionHash = transactions.collectToken(tenantId,
                "STARKNET-COLLECT-ETH", profile, deposit, token, hot.getAddress(), new BigDecimal("1"));
        assertTrue(transactions.confirmCollection(tenantId, profile,
                "STARKNET-COLLECT-ETH", "ETH_DEVNET"));

        assertEquals(nativeCollectionHash, repository.collectionHashes.get("STARKNET-COLLECT-STRK"));
        assertEquals(tokenCollectionHash, repository.collectionHashes.get("STARKNET-COLLECT-ETH"));
        assertTrue(repository.recordedTransactions > 0);
        assertEquals(2, repository.creditedDeposits.size());
        assertTrue(rpc.balance(profile, STRK, hot.getAddress()).signum() > 0);
        assertTrue(rpc.balance(profile, ETH, hot.getAddress()).signum() > 0);
    }

    /** 构造与项目真实地址服务相同派生规则的测试地址。 */
    private static ChainAddressRecord address(StarknetKeyService keys, AccountChainProfile profile,
                                              long userId, int biz, long index, String role) {
        return keys.derive(profile, userId, biz, index).toAddressRecord(profile, userId, biz, index, role);
    }

    /** 检查 Devnet 可访问，避免把网络错误误判为业务失败。 */
    private static void ping(String rpcUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rpcUrl + "/rpc"))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"starknet_chainId\",\"params\":[]}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("0x534e5f5345504f4c4941"));
    }

    /** 使用 Devnet 扩展 JSON-RPC 方法向测试账户发放 STRK 或 ETH。 */
    private static void mint(String rpcUrl, String address, String amount, String unit) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"devnet_mint\","
                + "\"params\":{\"address\":\"" + address + "\",\"amount\":" + amount
                + ",\"unit\":\"" + unit + "\"}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(rpcUrl + "/rpc"))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("result"), response.body());
    }

    /** 构造与 Devnet 一致的 Starknet Sepolia 配置。 */
    private static AccountChainProfile profile() {
        return AccountChainProfile.builder()
                .chain("STARKNET")
                .network("sepolia")
                .family("starknet")
                .bip44CoinType(9004)
                .nativeSymbol("STRK")
                .accountClassHash(ACCOUNT_CLASS_HASH)
                .depositConfirmations(1)
                .withdrawConfirmations(1)
                .scanBatchSize(100)
                .scanMaxBlocksPerRun(100L)
                .scanStartHeight(0L)
                .scanEnabled(true)
                .withdrawEnabled(true)
                .collectionEnabled(true)
                .transferEnabled(true)
                .build();
    }

    /** 仅替换 RPC 节点发现，所有请求仍然经过真实 Starknet JVM SDK。 */
    private static final class DevnetRpcNodeService extends ChainRpcNodeService {
        /** 本地 Devnet 节点。 */
        private final ChainRpcNode node;

        /** 创建指向本地 Devnet 的节点服务。 */
        private DevnetRpcNodeService(String rpcUrl) {
            super(null);
            this.node = ChainRpcNode.builder()
                    .chain("STARKNET")
                    .network("sepolia")
                    .environment("dev")
                    .nodeLabel("starknet-devnet")
                    .purpose("rpc")
                    .connectionType("HTTP_JSON_RPC")
                    .rpcUrl(rpcUrl)
                    .authType("NONE")
                    .priority(1)
                    .minRequestIntervalMs(0)
                    .enabled(true)
                    .build();
        }

        /** 将所有用途的请求定向到本地 Devnet。 */
        @Override
        public <T> T withFailover(String chain, String network, String purpose,
                                  Function<ChainRpcNode, T> request) {
            return request.apply(node);
        }
    }

    /** 只保存本次链路所需数据，避免测试依赖或清理开发数据库。 */
    private static final class InMemoryRepository extends ChainJdbcRepository {
        /** 测试充值地址。 */
        private final List<ChainAddressRecord> addresses = new ArrayList<>();
        /** 测试入账事件。 */
        private final List<DepositEvent> creditedDeposits = new ArrayList<>();
        /** 测试归集交易。 */
        private final Map<String, String> collectionHashes = new HashMap<>();
        /** 交易记录数。 */
        private int recordedTransactions;
        /** 提现交易哈希。 */
        private String withdrawalHash;
        /** 测试 Token。 */
        private TokenDefinition token;

        /** 使用空 JDBC 句柄构造测试仓储，所有被测方法均在本类内隔离实现。 */
        private InMemoryRepository() {
            super((JdbcTemplate) null);
        }

        /** 返回本地 STRK 资产配置。 */
        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            if (!"STARKNET".equalsIgnoreCase(chain) || !"STRK".equalsIgnoreCase(symbol)) {
                return Optional.empty();
            }
            return Optional.of(ChainAsset.builder().chain("STARKNET").symbol("STRK")
                    .contractAddress(STRK).decimals(18).nativeAsset(true).active(true).build());
        }

        /** 返回本次测试中启用的 Starknet Token。 */
        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return token == null ? List.of() : List.of(token);
        }

        /** 返回充值地址。 */
        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
            return addresses;
        }

        /** 返回本次测试中全部 Starknet 地址，覆盖原生资产和 Token 共用地址场景。 */
        @Override
        public List<ChainAddressRecord> listChainAddresses(String chain) {
            return addresses;
        }

        /** 保存交易审计记录。 */
        @Override
        public int recordStarknetTransaction(StarknetTransactionRecord transaction) {
            recordedTransactions++;
            return 1;
        }

        /** 保存交易确认信息。 */
        @Override
        public int markStarknetTransactionConfirmed(String chain, String txHash, BigDecimal fee,
                                                     Long blockHeight, int confirmations,
                                                     String rawPayload) {
            return 1;
        }

        /** 返回扫描起点为空，让本次测试扫描 Devnet 当前完整窗口。 */
        @Override
        public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
            return Optional.empty();
        }

        /** 接收扫描高度。 */
        @Override
        public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        }

        /** 接收规范区块观察记录。 */
        @Override
        public BlockObservation observeCanonicalBlock(String chain, String scannerName,
                                                      long blockHeight, String blockHash,
                                                      String parentHash) {
            return new BlockObservation(false, null, blockHash, 0);
        }

        /** 记录充值事件并模拟成功入账。 */
        @Override
        public boolean recordAndCreditDeposit(DepositEvent event, long logIndex,
                                              int requiredConfirmations, String accountId) {
            creditedDeposits.add(event);
            return true;
        }

        /** 返回待确认提现哈希。 */
        @Override
        public Optional<String> findWithdrawalTxHash(UUID tenantId, String chain, String orderNo) {
            return Optional.ofNullable(withdrawalHash);
        }

        /** 模拟提现确认和账务结算成功。 */
        @Override
        public boolean confirmWithdrawalAndSettle(UUID tenantId, String chain, String orderNo,
                                                  String txHash, String assetSymbol,
                                                  String accountId, BigDecimal amount) {
            return true;
        }

        /** 读取归集交易哈希。 */
        @Override
        public Optional<String> findCollectionTxHash(UUID tenantId, String chain, String collectionNo) {
            return Optional.ofNullable(collectionHashes.get(collectionNo));
        }

        /** 模拟归集签名抢占。 */
        @Override
        public int claimCollectionSigning(UUID tenantId, String chain, String collectionNo,
                                           String rawPayload) {
            return collectionHashes.containsKey(collectionNo) ? 0 : 1;
        }

        /** 保存归集广播状态。 */
        @Override
        public int updateCollectionStatus(UUID tenantId, String chain, String collectionNo,
                                          String status, String txHash, String errorMessage,
                                          String rawPayload) {
            if (txHash != null) {
                collectionHashes.put(collectionNo, txHash);
            }
            return 1;
        }

        /** 模拟归集确认成功。 */
        @Override
        public int markCollectionConfirmed(UUID tenantId, String chain, String collectionNo,
                                           String txHash) {
            return 1;
        }
    }
}
