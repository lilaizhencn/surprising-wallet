package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptosTestnetConfigurationIntegrationTest {

    @Test
    void configuredFaMetadataAndLiveScannerMatchTestnet() {
        Assumptions.assumeTrue(Boolean.getBoolean("aptos.testnet.config.live.enabled"),
                "set -Daptos.testnet.config.live.enabled=true for Aptos Testnet RPC validation");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("APTOS_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("APTOS_DB_USER", "wallet"),
                env("APTOS_DB_PASSWORD", ""));
        ChainJdbcRepository repository = new ChainJdbcRepository(new JdbcTemplate(dataSource));
        AptosRpcClient rpc = new AptosRpcClient(new ObjectMapper(),
                env("APTOS_RPC_URL", "https://fullnode.testnet.aptoslabs.com/v1"), "");

        assertEquals("testnet", repository.findProfileByChain("APTOS").orElseThrow().getNetwork());
        assertEquals(2, rpc.chainId());
        Map<String, TokenDefinition> tokens = repository.listTokens("APTOS").stream()
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
            repository.updateScanHeight("APTOS", AptosDepositScanner.SCANNER, ledgerVersion, ledgerVersion);
            new AptosDepositScanner(rpc, repository).scanAndCredit();
            assertTrue(repository.findScanSafeHeight("APTOS", AptosDepositScanner.SCANNER).isPresent());
            status.setRollbackOnly();
        });
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
