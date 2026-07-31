package com.surprising.wallet.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.config.WalletStartupValidator;

/**
 * 验证 {@code WalletStartupValidatorTest} 覆盖的业务流程、边界条件和异常行为。
 */
class WalletStartupValidatorTest {
    /**
     * 验证 {@code enabledProfilesRejectPlaceholderRpcNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledProfilesRejectPlaceholderRpcNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("ADA", "preprod")),
                List.of(node("blockfrost-cardano-preprod",
                        "https://cardano-preprod.blockfrost.io/api/v0",
                        "CHANGE_ME_BLOCKFROST_PREPROD_PROJECT_ID")),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("placeholder"));
        assertTrue(error.getMessage().contains("ADA/preprod"));
    }

    /**
     * 验证 {@code enabledProfilesAcceptConfiguredRpcNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledProfilesAcceptConfiguredRpcNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("NEAR", "testnet")),
                List.of(node("NEAR", "rpc", "near-testnet-official", "HTTP_JSON_RPC",
                        "NONE", "https://rpc.testnet.near.org", null, null, null)),
                List.of()));

        assertDoesNotThrow(validator::validateProfiles);
    }

    /**
     * 验证 {@code enabledEvmProfileAcceptsExplicitGasAndFeeModels} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledEvmProfileAcceptsExplicitGasAndFeeModels() throws Exception {
        AccountChainProfile profile = evmProfile("BASE", "sepolia", "ETH_BASE",
                "eip1559", "op-stack");
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile),
                List.of(node("BASE", "sepolia", "rpc", "base-sepolia",
                        "https://base-sepolia-rpc.publicnode.com")),
                List.of()));

        assertDoesNotThrow(validator::validateProfiles);
    }

    /**
     * 验证 {@code activeEip7702ConfigAcceptsMatchingEnabledEvmProfile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void activeEip7702ConfigAcceptsMatchingEnabledEvmProfile() throws Exception {
        AccountChainProfile profile = evmProfile("BASE", "sepolia", "ETH_BASE",
                "eip1559", "op-stack");
        WalletStartupValidator validator = validator(
                new FakeRepository(
                        List.of(profile),
                        List.of(node("BASE", "sepolia", "rpc", "base-sepolia",
                                "https://base-sepolia-rpc.publicnode.com")),
                        List.of()),
                new FakeJdbcTemplate(
                        List.of(), List.of(), List.of(),
                        List.of(Map.of("chain", "BASE", "network", "sepolia"))));

        assertDoesNotThrow(validator::validateProfiles);
    }

    /**
     * 验证 {@code enabledEvmProfileRejectsAmbiguousOldL2GasPolicy} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledEvmProfileRejectsAmbiguousOldL2GasPolicy() throws Exception {
        AccountChainProfile profile = evmProfile("BASE", "sepolia", "ETH_BASE",
                "eip1559-l2", "op-stack");
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile), List.of(), List.of()));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("invalid fee policy"));
    }

    /**
     * 验证 {@code testEnvironmentRejectsMultipleEnabledNetworksForOneChain} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void testEnvironmentRejectsMultipleEnabledNetworksForOneChain() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("ETH", "devnet"), profile("ETH", "testnet")),
                List.of(
                        node("ETH", "devnet", "rpc", "eth-devnet", "http://127.0.0.1:8545"),
                        node("ETH", "testnet", "rpc", "eth-testnet", "https://rpc.testnet.example")),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("multiple enabled networks"));
    }

    /**
     * 验证 {@code productionRejectsMultipleEnabledNetworksForOneChain} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void productionRejectsMultipleEnabledNetworksForOneChain() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("ETH", "mainnet"), profile("ETH", "main")),
                List.of(),
                List.of()));
        setField(validator, "environmentName", "prod");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("multiple enabled networks"));
        assertTrue(error.getMessage().contains("ETH"));
    }

    /**
     * 验证 {@code productionRejectsTestNetwork} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void productionRejectsTestNetwork() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("ETH", "testnet")),
                List.of(),
                List.of()));
        setField(validator, "environmentName", "production");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("cannot enable test network"));
    }

    /**
     * 验证 {@code dotProfilesRequireRuntimeServiceNode} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void dotProfilesRequireRuntimeServiceNode() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("DOT", "westend")),
                List.of(node("DOT", "rpc", "polkadot-westend-ws", "WS_RPC",
                        "NONE", "wss://westend-rpc.polkadot.io", null, null, null)),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("purpose=runtime"));
        assertTrue(error.getMessage().contains("DOT/westend"));
    }

    /**
     * 验证 {@code hyperCoreProfilesRequireInfoAndExchangeNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void hyperCoreProfilesRequireInfoAndExchangeNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("HYPERCORE", "testnet")),
                List.of(node("HYPERCORE", "info", "hyperliquid-testnet-info", "HYPERLIQUID_INFO",
                        "NONE", "https://api.hyperliquid-testnet.xyz", null, null, null)),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("purpose=exchange"));
        assertTrue(error.getMessage().contains("HYPERCORE/testnet"));
    }

    /**
     * 验证 {@code hyperCoreProfilesAcceptInfoAndExchangeNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void hyperCoreProfilesAcceptInfoAndExchangeNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("HYPERCORE", "testnet")),
                List.of(
                        node("HYPERCORE", "info", "hyperliquid-testnet-info", "HYPERLIQUID_INFO",
                                "NONE", "https://api.hyperliquid-testnet.xyz", null, null, null),
                        node("HYPERCORE", "exchange", "hyperliquid-testnet-exchange", "HYPERLIQUID_EXCHANGE",
                                "NONE", "https://api.hyperliquid-testnet.xyz", null, null, null)),
                List.of()));

        assertDoesNotThrow(validator::validateProfiles);
    }

    /**
     * 验证 {@code cardanoBlockfrostNodesRequireProjectId} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void cardanoBlockfrostNodesRequireProjectId() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("ADA", "preprod")),
                List.of(node("ADA", "rpc", "blockfrost-cardano-preprod", "BLOCKFROST",
                        "PROJECT_ID", "https://cardano-preprod.blockfrost.io/api/v0", null, null, null)),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("api_key"));
        assertTrue(error.getMessage().contains("ADA/preprod"));
    }

    /**
     * 验证 {@code digestWalletRpcNodesRequireCredentials} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void digestWalletRpcNodesRequireCredentials() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("XMR", "regtest")),
                List.of(node("XMR", "rpc", "local-monero-wallet-rpc-regtest", "WALLET_RPC",
                        "DIGEST", "http://127.0.0.1:18088", null, "wallet", null)),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("username/password"));
        assertTrue(error.getMessage().contains("XMR/regtest"));
    }

    /**
     * 验证 {@code xmrRegtestProfilesRequireFaucetAndDaemonNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void xmrRegtestProfilesRequireFaucetAndDaemonNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("XMR", "regtest")),
                List.of(node("XMR", "rpc", "local-monero-wallet-rpc-regtest", "WALLET_RPC",
                        "NONE", "http://127.0.0.1:18088", null, null, null)),
                List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("purpose=faucet"));
        assertTrue(error.getMessage().contains("XMR/regtest"));
    }

    /**
     * 验证 {@code xmrRegtestProfilesAcceptConfiguredRpcFaucetAndDaemonNodes} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void xmrRegtestProfilesAcceptConfiguredRpcFaucetAndDaemonNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("XMR", "regtest")),
                List.of(
                        node("XMR", "rpc", "local-monero-wallet-rpc-regtest", "WALLET_RPC",
                                "NONE", "http://127.0.0.1:18088", null, null, null),
                        node("XMR", "faucet", "local-monero-wallet-rpc-funder-regtest", "WALLET_RPC",
                                "NONE", "http://127.0.0.1:18090", null, null, null),
                        node("XMR", "daemon", "local-monerod-regtest", "HTTP_JSON_RPC",
                                "NONE", "http://127.0.0.1:18081", null, null, null)),
                List.of()));

        assertDoesNotThrow(validator::validateProfiles);
    }

    /**
     * 验证 {@code placeholderDetectionCoversCommonSeedMarkers} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void placeholderDetectionCoversCommonSeedMarkers() {
        assertTrue(WalletStartupValidator.containsPlaceholder("https://example.com/CHANGE_ME_KEY"));
        assertTrue(WalletStartupValidator.containsPlaceholder("YOUR_API_KEY"));
        assertTrue(WalletStartupValidator.containsPlaceholder("replace_me"));
    }

    /**
     * 验证 {@code enabledTokenConfigRejectsPlaceholderContracts} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledTokenConfigRejectsPlaceholderContracts() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(Map.of("chain", "NEAR", "symbol", "USDC",
                                "contract_address", "CHANGE_ME_USDC_CONTRACT.testnet")),
                        List.of()));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validateEnabledAssetsAndTokens);

        assertTrue(error.getMessage().contains("enabled token_config"));
        assertTrue(error.getMessage().contains("NEAR/USDC"));
    }

    /**
     * 验证 {@code activeTokenAssetRejectsMissingContracts} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void activeTokenAssetRejectsMissingContracts() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(),
                        List.of(Map.of("chain", "ADA", "symbol", "USDT"))));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validateEnabledAssetsAndTokens);

        assertTrue(error.getMessage().contains("active token chain_asset"));
        assertTrue(error.getMessage().contains("ADA/USDT"));
    }

    /**
     * 验证 {@code activeTokenAssetRejectsMissingEnabledTokenConfig} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void activeTokenAssetRejectsMissingEnabledTokenConfig() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(),
                        List.of(Map.of("chain", "DOT", "symbol", "USDC",
                                "contract_address", "1984"))));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validateEnabledAssetsAndTokens);

        assertTrue(error.getMessage().contains("enabled token_config"));
        assertTrue(error.getMessage().contains("DOT/USDC"));
    }

    /**
     * 验证 {@code activeTokenAssetRejectsMismatchedTokenConfigContract} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void activeTokenAssetRejectsMismatchedTokenConfigContract() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(Map.of("chain", "NEAR", "symbol", "USDC",
                                "contract_address", "usdc.fakes.testnet")),
                        List.of(Map.of("chain", "NEAR", "symbol", "USDC",
                                "contract_address", "usdc.real.testnet"))));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validateEnabledAssetsAndTokens);

        assertTrue(error.getMessage().contains("contract must match"));
        assertTrue(error.getMessage().contains("NEAR/USDC"));
    }

    /**
     * 验证 {@code activeTokenAssetAcceptsMatchingTokenConfigContract} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void activeTokenAssetAcceptsMatchingTokenConfigContract() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(Map.of("chain", "ADA", "symbol", "USDC",
                                "network", "preprod",
                                "contract_address", "abcd.Token")),
                        List.of(Map.of("chain", "ADA", "symbol", "USDC",
                                "contract_address", "ABCD.token")),
                        List.of(Map.of("chain", "ADA", "network", "preprod"))));

        assertDoesNotThrow(validator::validateEnabledAssetsAndTokens);
    }

    /**
     * 验证 {@code enabledTokenConfigRejectsNetworkMismatchWithEnabledProfile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledTokenConfigRejectsNetworkMismatchWithEnabledProfile() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(Map.of("chain", "NEAR", "symbol", "USDC",
                                "network", "mainnet",
                                "contract_address", "usdc.testnet")),
                        List.of(),
                        List.of(Map.of("chain", "NEAR", "network", "testnet"))));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validateEnabledAssetsAndTokens);

        assertTrue(error.getMessage().contains("network must match"));
        assertTrue(error.getMessage().contains("NEAR/USDC"));
    }

    /**
     * 验证 {@code enabledTokenConfigMatchesAnyEnabledTestNetworkProfile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledTokenConfigMatchesAnyEnabledTestNetworkProfile() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(Map.of("chain", "ETH", "symbol", "USDC",
                                "network", "testnet", "contract_address", "0x1234")),
                        List.of(Map.of("chain", "ETH", "symbol", "USDC",
                                "contract_address", "0x1234")),
                        List.of(
                                Map.of("chain", "ETH", "network", "devnet"),
                                Map.of("chain", "ETH", "network", "testnet"))));

        assertDoesNotThrow(validator::validateEnabledAssetsAndTokens);
    }

    /**
     * 验证 {@code enabledTokenConfigAllowsBlankNetworkForLegacyRows} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void enabledTokenConfigAllowsBlankNetworkForLegacyRows() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(List.of(), List.of(), List.of()),
                new FakeJdbcTemplate(
                        List.of(Map.of("chain", "ETH", "symbol", "USDC",
                                "contract_address", "0x9478ec397a2f4be6a84916dd8a353c91b78c6238")),
                        List.of(),
                        List.of()));

        assertDoesNotThrow(validator::validateEnabledAssetsAndTokens);
    }

    /**
     * 验证 {@code validator} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static WalletStartupValidator validator(ChainJdbcRepository repository) throws Exception {
        return validator(repository, new FakeJdbcTemplate(List.of(), List.of()));
    }

    /**
     * 验证 {@code validator} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static WalletStartupValidator validator(ChainJdbcRepository repository,
                                                    JdbcTemplate jdbcTemplate) throws Exception {
        WalletStartupValidator validator = new WalletStartupValidator(repository, null, jdbcTemplate, null);
        setField(validator, "environmentName", "test2");
        return validator;
    }

    /**
     * 验证 {@code setField} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = WalletStartupValidator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 验证 {@code profile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainProfile profile(String chain, String network) {
        return AccountChainProfile.builder()
                .chain(chain)
                .network(network)
                .family(chain.toLowerCase())
                .nativeSymbol(chain)
                .enabled(true)
                .scanEnabled(true)
                .withdrawEnabled(true)
                .collectionEnabled(true)
                .transferEnabled(true)
                .build();
    }

    /**
     * 验证 {@code evmProfile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainProfile evmProfile(
            String chain, String network, String nativeSymbol,
            String gasPolicy, String feeModel) {
        return AccountChainProfile.builder()
                .chain(chain)
                .network(network)
                .family("evm")
                .nativeSymbol(nativeSymbol)
                .chainId(84532L)
                .gasPolicy(gasPolicy)
                .feeModel(feeModel)
                .enabled(true)
                .scanEnabled(true)
                .withdrawEnabled(true)
                .collectionEnabled(true)
                .transferEnabled(true)
                .build();
    }

    /**
     * 验证 {@code node} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ChainRpcNode node(String label, String rpcUrl, String apiKey) {
        return node("ADA", "rpc", label, "HTTP_JSON_RPC", "NONE", rpcUrl, apiKey, null, null);
    }

    /**
     * 验证 {@code node} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ChainRpcNode node(String chain, String purpose, String label, String connectionType,
                                     String authType, String rpcUrl, String apiKey,
                                     String username, String password) {
        return ChainRpcNode.builder()
                .chain(chain)
                .network(network(chain))
                .environment("test2")
                .nodeLabel(label)
                .purpose(purpose)
                .connectionType(connectionType)
                .rpcUrl(rpcUrl)
                .authType(authType)
                .apiKey(apiKey)
                .username(username)
                .password(password)
                .enabled(true)
                .build();
    }

    /**
     * 验证 {@code node} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ChainRpcNode node(String chain, String network, String purpose,
                                     String label, String rpcUrl) {
        return ChainRpcNode.builder()
                .chain(chain)
                .network(network)
                .environment("test2")
                .nodeLabel(label)
                .purpose(purpose)
                .connectionType("HTTP_JSON_RPC")
                .rpcUrl(rpcUrl)
                .authType("NONE")
                .enabled(true)
                .build();
    }

    /**
     * 验证 {@code network} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String network(String chain) {
        return switch (chain) {
            case "DOT" -> "westend";
            case "NEAR" -> "testnet";
            case "XMR" -> "regtest";
            case "HYPERCORE" -> "testnet";
            case "BTC" -> "testnet3";
            default -> "preprod";
        };
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code profiles}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<AccountChainProfile> profiles;
        /**
         * 保存 {@code nodes}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<ChainRpcNode> nodes;
        /**
         * 保存 {@code tokens}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final List<TokenDefinition> tokens;

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeRepository(List<AccountChainProfile> profiles, List<ChainRpcNode> nodes,
                               List<TokenDefinition> tokens) {
            super(null);
            this.profiles = profiles;
            this.nodes = nodes;
            this.tokens = tokens;
        }

        /**
         * 验证 {@code listEnabledChainProfiles} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<AccountChainProfile> listEnabledChainProfiles() {
            return profiles;
        }

        /**
         * 验证 {@code listEnabledRpcNodes} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network, String environment) {
            return listEnabledRpcNodes(chain, network, environment, "rpc");
        }

        /**
         * 验证 {@code listEnabledRpcNodes} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network,
                                                      String environment, String purpose) {
            return nodes.stream()
                    .filter(node -> node.getChain().equalsIgnoreCase(chain))
                    .filter(node -> node.getNetwork().equalsIgnoreCase(network))
                    .filter(node -> node.getPurpose().equalsIgnoreCase(purpose))
                    .toList();
        }

        /**
         * 验证 {@code listTokens} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return tokens.stream()
                    .filter(token -> token.getChain().equalsIgnoreCase(chain))
                    .toList();
        }

        /**
         * 验证 {@code findAsset} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return profiles.stream()
                    .filter(profile -> profile.getChain().equalsIgnoreCase(chain))
                    .filter(profile -> profile.getNativeSymbol().equalsIgnoreCase(symbol))
                    .findFirst()
                    .map(profile -> ChainAsset.builder()
                            .chain(chain)
                            .symbol(symbol)
                            .decimals(18)
                            .nativeAsset(true)
                            .active(true)
                            .build());
        }

        /**
         * 验证 {@code countActiveNativeAssets} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int countActiveNativeAssets(String chain) {
            return profiles.stream()
                    .anyMatch(profile -> profile.getChain().equalsIgnoreCase(chain)) ? 1 : 0;
        }
    }

    /**
     * 测试替身 {@code FakeJdbcTemplate}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeJdbcTemplate extends JdbcTemplate {
        /**
         * 保存 {@code tokenRows}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final List<Map<String, Object>> tokenRows;
        /**
         * 保存 {@code assetRows}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final List<Map<String, Object>> assetRows;
        /**
         * 保存 {@code profileRows}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<Map<String, Object>> profileRows;
        /**
         * 保存 {@code eip7702Rows}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<Map<String, Object>> eip7702Rows;

        /**
         * 验证 {@code FakeJdbcTemplate} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeJdbcTemplate(List<Map<String, Object>> tokenRows,
                                 List<Map<String, Object>> assetRows) {
            this(tokenRows, assetRows, List.of(), List.of());
        }

        /**
         * 验证 {@code FakeJdbcTemplate} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeJdbcTemplate(List<Map<String, Object>> tokenRows,
                                 List<Map<String, Object>> assetRows,
                                 List<Map<String, Object>> profileRows) {
            this(tokenRows, assetRows, profileRows, List.of());
        }

        /**
         * 验证 {@code FakeJdbcTemplate} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeJdbcTemplate(List<Map<String, Object>> tokenRows,
                                 List<Map<String, Object>> assetRows,
                                 List<Map<String, Object>> profileRows,
                                 List<Map<String, Object>> eip7702Rows) {
            this.tokenRows = tokenRows;
            this.assetRows = assetRows;
            this.profileRows = profileRows;
            this.eip7702Rows = eip7702Rows;
        }

        /**
         * 验证 {@code queryForList} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            if (sql.contains("from token_config")) {
                return tokenRows;
            }
            if (sql.contains("from chain_asset")) {
                return assetRows;
            }
            if (sql.contains("from chain_profile")) {
                return profileRows;
            }
            if (sql.contains("from evm_7702_config")) {
                return eip7702Rows;
            }
            if (sql.contains("evm_7702")) {
                throw new AssertionError("unexpected EIP-7702 table in startup query: " + sql);
            }
            return List.of();
        }
    }
}
