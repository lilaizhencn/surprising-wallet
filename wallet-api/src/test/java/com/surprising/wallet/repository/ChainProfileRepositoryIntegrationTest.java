package com.surprising.wallet.repository;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.surprising.wallet.custody.CustodyIntegrationDatabase;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class ChainProfileRepositoryIntegrationTest {
    @Test
    void insertsUpdatesAndReadsChainConfigurationAgainstCurrentSchema() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainProfileRepository repository = new ChainProfileRepository(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            long id = repository.insert("REPO_TEST", "testnet", "evm", 987654, 60,
                    "GAS", null, 3, 5, 1L, 0L, true, 123L, "eip1559", "standard",
                    25, true, true, true, true, 12L, 34L);

            assertEquals("REPO_TEST", repository.findById(id).orElseThrow().getChain());
            assertTrue(repository.findByChainAndNetwork("REPO_TEST", "testnet").isPresent());
            assertTrue(repository.listAll().stream().anyMatch(row -> "REPO_TEST".equals(row.getChain())));
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
    @Test
    void resetRejectsNonTestDatabaseBeforeOpeningAConnection() throws Exception {
        var source = org.mockito.Mockito.mock(DriverManagerDataSource.class);
        org.mockito.Mockito.when(source.getUrl()).thenReturn("jdbc:postgresql://127.0.0.1:5432/not_a_test_database");
        assertThrows(IllegalStateException.class, () -> CustodyIntegrationDatabase.reset(source));
        org.mockito.Mockito.verify(source, org.mockito.Mockito.never()).getConnection();
    }

}
