package com.surprising.wallet.jobs.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletStartupValidatorTest {
    private static final String TEST_ED25519_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

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

    @Test
    void enabledProfilesAcceptConfiguredRpcNodes() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                List.of(profile("NEAR", "testnet")),
                List.of(node("NEAR", "rpc", "near-testnet-official", "HTTP_JSON_RPC",
                        "NONE", "https://rpc.testnet.near.org", null, null, null)),
                List.of()));

        assertDoesNotThrow(validator::validateProfiles);
    }

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

    @Test
    void ed25519ProfilesRequireConfiguredSeed() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                        List.of(profile("ADA", "preprod")),
                        List.of(node("ADA", "rpc", "blockfrost-cardano-preprod", "BLOCKFROST",
                                "PROJECT_ID", "https://cardano-preprod.blockfrost.io/api/v0",
                                "project-id", null, null)),
                        List.of()),
                null,
                "");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validateProfiles);

        assertTrue(error.getMessage().contains("SW_ED25519_SEED"));
    }

    @Test
    void nonEd25519ProfilesDoNotRequireConfiguredSeed() throws Exception {
        WalletStartupValidator validator = validator(new FakeRepository(
                        List.of(profile("BTC", "testnet3")),
                        List.of(node("BTC", "rpc", "bitcoin-testnet-rpc", "HTTP_JSON_RPC",
                                "NONE", "https://btc-testnet.example.com", null, null, null)),
                        List.of()),
                null,
                "");

        assertDoesNotThrow(validator::validateProfiles);
    }

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

    @Test
    void placeholderDetectionCoversCommonSeedMarkers() {
        assertTrue(WalletStartupValidator.containsPlaceholder("https://example.com/CHANGE_ME_KEY"));
        assertTrue(WalletStartupValidator.containsPlaceholder("YOUR_API_KEY"));
        assertTrue(WalletStartupValidator.containsPlaceholder("replace_me"));
    }

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

    private static WalletStartupValidator validator(ChainJdbcRepository repository) throws Exception {
        return validator(repository, null);
    }

    private static WalletStartupValidator validator(ChainJdbcRepository repository,
                                                    JdbcTemplate jdbcTemplate) throws Exception {
        return validator(repository, jdbcTemplate, TEST_ED25519_SEED);
    }

    private static WalletStartupValidator validator(ChainJdbcRepository repository,
                                                    JdbcTemplate jdbcTemplate,
                                                    String ed25519MasterSeed) throws Exception {
        WalletStartupValidator validator = new WalletStartupValidator(repository, null, null, jdbcTemplate);
        setField(validator, "environmentName", "test2");
        setField(validator, "ed25519MasterSeed", ed25519MasterSeed);
        return validator;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = WalletStartupValidator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

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

    private static ChainRpcNode node(String label, String rpcUrl, String apiKey) {
        return node("ADA", "rpc", label, "HTTP_JSON_RPC", "NONE", rpcUrl, apiKey, null, null);
    }

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

    private static String network(String chain) {
        return switch (chain) {
            case "DOT" -> "westend";
            case "NEAR" -> "testnet";
            case "XMR" -> "regtest";
            case "BTC" -> "testnet3";
            default -> "preprod";
        };
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final List<AccountChainProfile> profiles;
        private final List<ChainRpcNode> nodes;
        private final List<TokenDefinition> tokens;

        private FakeRepository(List<AccountChainProfile> profiles, List<ChainRpcNode> nodes,
                               List<TokenDefinition> tokens) {
            super(null);
            this.profiles = profiles;
            this.nodes = nodes;
            this.tokens = tokens;
        }

        @Override
        public List<AccountChainProfile> listEnabledChainProfiles() {
            return profiles;
        }

        @Override
        public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network, String environment) {
            return listEnabledRpcNodes(chain, network, environment, "rpc");
        }

        @Override
        public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network,
                                                      String environment, String purpose) {
            return nodes.stream()
                    .filter(node -> node.getChain().equalsIgnoreCase(chain))
                    .filter(node -> node.getNetwork().equalsIgnoreCase(network))
                    .filter(node -> node.getPurpose().equalsIgnoreCase(purpose))
                    .toList();
        }

        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return tokens.stream()
                    .filter(token -> token.getChain().equalsIgnoreCase(chain))
                    .toList();
        }
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final List<Map<String, Object>> tokenRows;
        private final List<Map<String, Object>> assetRows;
        private final List<Map<String, Object>> profileRows;

        private FakeJdbcTemplate(List<Map<String, Object>> tokenRows,
                                 List<Map<String, Object>> assetRows) {
            this(tokenRows, assetRows, List.of());
        }

        private FakeJdbcTemplate(List<Map<String, Object>> tokenRows,
                                 List<Map<String, Object>> assetRows,
                                 List<Map<String, Object>> profileRows) {
            this.tokenRows = tokenRows;
            this.assetRows = assetRows;
            this.profileRows = profileRows;
        }

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
            return List.of();
        }
    }
}
