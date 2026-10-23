package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import com.surprising.wallet.testutil.RuntimeTestSupport;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.surprising.wallet.testutil.RpcTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code AptosTestnetConfigurationIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class AptosTestnetConfigurationIntegrationTest {

    /**
     * 验证 {@code configuredFaMetadataAndLiveScannerMatchTestnet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void configuredFaMetadataAndLiveScannerMatchTestnet() {
        Assumptions.assumeTrue(Boolean.getBoolean("aptos.testnet.config.live.enabled"),
                "set -Daptos.testnet.config.live.enabled=true for Aptos Testnet RPC validation");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("APTOS_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("APTOS_DB_USER", "wallet"),
                env("APTOS_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainRepositoryFixture repository = com.surprising.wallet.testutil.DepositTestSupport.repository(jdbc);
        AptosRpcClient rpc = new AptosRpcClient(
                new ObjectMapper(),
                profiles("APTOS"),
                nodes("APTOS", java.util.Map.of(
                        "rpc",
                        node("APTOS", env("APTOS_RPC_URL", "https://fullnode.testnet.aptoslabs.com/v1")),
                        "faucet",
                        node("APTOS", ""))));

        assertEquals("testnet", repository.chainProfileRepository.findAccountByChain("APTOS").orElseThrow().getNetwork());
        assertEquals(2, rpc.chainId());
        Map<String, TokenDefinition> tokens = repository.chainProfileRepository.findAccountByChain("APTOS").map( configuredNetworkProfile -> repository.tokenConfigRepository.listDefinitions("APTOS", configuredNetworkProfile.getNetwork())).orElse(List.of()).stream()
                .collect(Collectors.toMap(TokenDefinition::getSymbol, Function.identity()));
        assertEquals(List.of("USDC", "USDT"), tokens.keySet().stream().sorted().toList());
        for (TokenDefinition token : tokens.values()) {
            assertEquals(AptosFungibleAsset.STANDARD, token.getStandard());
            JsonNode metadata = rpc.fungibleAssetMetadata(token.getContractAddress()).path("data");
            assertEquals(token.getSymbol(), metadata.path("symbol").asText().toUpperCase());
            assertEquals(token.getDecimals(), metadata.path("decimals").asInt());
        }

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            long ledgerVersion = rpc.ledgerVersion();
            repository.chainScanHeightRepository.upsert("APTOS", AptosDepositScanner.SCANNER, ledgerVersion, ledgerVersion);
            new AptosDepositScanner(
                    rpc,
                    repository.chainAddressRepository,
                    repository.chainProfileRepository,
                    repository.tokenConfigRepository,
                    repository.chainScanHeightRepository,
                    repository.aptosTransactionRepository,
                    repository.depositService,
                    repository.chainAssetRepository,
                    RuntimeTestSupport.database(jdbc)).scanAndCredit();
            assertTrue(repository.chainScanHeightRepository.findSafeHeight("APTOS", AptosDepositScanner.SCANNER).isPresent());
            status.setRollbackOnly();
        });
    }

    /**
     * 验证 {@code env} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
