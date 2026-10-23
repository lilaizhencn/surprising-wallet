package com.surprising.wallet.repository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainProfileRepositoryIntegrationTest {
    @Test
    void insertsUpdatesAndReadsChainConfigurationAgainstCurrentSchema() {
        String url = System.getProperty("wallet.test.db.url");
        Assumptions.assumeTrue(url != null, "set wallet.test.db.url to an isolated local test database");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/surprising_wallet_test_[a-z0-9_]+"));

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getProperty("wallet.test.db.user", System.getProperty("user.name")),
                System.getProperty("wallet.test.db.password", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainProfileRepository repository = new ChainProfileRepository(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            long id = repository.insert("REPO_TEST", "testnet", "evm", 987654, 60,
                    "GAS", null, 3, 5, 1L, 0L, true, 123L, "eip1559", "standard",
                    25, true, true, true, true, 12L, 34L);

            assertEquals("REPO_TEST", repository.findById(id).orElseThrow().get("chain"));
            assertTrue(repository.findByChainAndNetwork("REPO_TEST", "testnet").isPresent());
            assertTrue(repository.listAll().stream().anyMatch(row -> "REPO_TEST".equals(row.get("chain"))));
            assertEquals(25, repository.findAccount("REPO_TEST", "testnet").orElseThrow().getScanBatchSize());

            assertEquals(1, repository.update(id, "REPO_TEST", "testnet", "evm", 987654, 60,
                    "GAS", "https://example.invalid/tx/", 7, 9, 2L, 1L, true, 456L,
                    "legacy-gas-price", "standard", 50, false, true, false, true, 100L, 200L));

            var profile = repository.findAccount("REPO_TEST", "testnet").orElseThrow();
            assertEquals(456L, profile.getChainId());
            assertEquals("legacy-gas-price", profile.getGasPolicy());
            assertEquals("standard", profile.getFeeModel());
            assertEquals(50, profile.getScanBatchSize());
            assertEquals(100L, profile.getScanStartHeight());
            assertEquals(200L, profile.getScanMaxBlocksPerRun());
            assertEquals(false, profile.getScanEnabled());
            assertEquals(456L, repository.findAccountByChain("REPO_TEST").orElseThrow().getChainId());
        });
    }
}
