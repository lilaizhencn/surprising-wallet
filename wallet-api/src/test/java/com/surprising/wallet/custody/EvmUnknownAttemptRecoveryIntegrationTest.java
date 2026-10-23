package com.surprising.wallet.custody;

import com.surprising.wallet.repository.Evm7702CollectionRepository;
import com.surprising.wallet.repository.Evm7702WithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class EvmUnknownAttemptRecoveryIntegrationTest {
    private JdbcTemplate jdbc;
    private Evm7702CollectionRepository collections;
    private Evm7702WithdrawalRepository withdrawals;
    private UUID firstTenant;
    private UUID secondTenant;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        collections = new Evm7702CollectionRepository(jdbc);
        withdrawals = new Evm7702WithdrawalRepository(jdbc, collections);
        firstTenant = tenant();
        secondTenant = tenant();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void otherChainsNetworksAndTerminalBatchesCannotConsumeTheRecoveryWindow(boolean collection) {
        batch(collection, firstTenant, "BNB", "sepolia", "BROADCAST_UNKNOWN", 0);
        batch(collection, secondTenant, "ETH", "mainnet", "BROADCAST_UNKNOWN", 1);
        batch(collection, secondTenant, "ETH", "sepolia", "CONFIRMED", 2);
        UUID first = batch(collection, firstTenant, "ETH", "sepolia", "BROADCAST_UNKNOWN", 10);
        UUID second = batch(collection, secondTenant, "ETH", "sepolia", "BROADCAST_UNKNOWN", 20);

        assertEquals(List.of(new Observation(firstTenant, first, "cipher-" + first)), observations(collection, 1));
        assertEquals(List.of(new Observation(firstTenant, first, "cipher-" + first),
                new Observation(secondTenant, second, "cipher-" + second)), observations(collection, 2));
        assertTrue(collection ? collections.listUnknownAttempts("ETH", "absent", 1).isEmpty()
                : withdrawals.listUnknownAttempts("ETH", "absent", 1).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void recoveryKeepsCooldownOrderingLimitAndTheOriginalTenantAndCiphertext(boolean collection) {
        UUID coolingDown = batch(collection, firstTenant, "ETH", "sepolia", "BROADCAST_UNKNOWN", 0);
        jdbc.update("update " + attemptTable(collection) + " set last_rebroadcast_at = now() where batch_id = ?", coolingDown);
        UUID first = batch(collection, secondTenant, "ETH", "sepolia", "BROADCAST_UNKNOWN", 10);
        UUID second = batch(collection, firstTenant, "ETH", "sepolia", "BROADCAST_UNKNOWN", 20);
        jdbc.update("update " + attemptTable(collection) + " set last_rebroadcast_at = now() - interval '1 minute' where batch_id = ?", second);

        assertEquals(List.of(new Observation(secondTenant, first, "cipher-" + first)), observations(collection, 0));
        if (collection) collections.recordRecoveryAttempt(collections.listUnknownAttempts("ETH", "sepolia", 1).getFirst());
        else withdrawals.recordRecoveryAttempt(withdrawals.listUnknownAttempts("ETH", "sepolia", 1).getFirst());
        assertEquals(List.of(new Observation(firstTenant, second, "cipher-" + second)), observations(collection, 1));
        assertEquals(1, jdbc.queryForObject("select rebroadcast_count from " + attemptTable(collection) + " where batch_id = ?", Integer.class, first));
        assertEquals(0, jdbc.queryForObject("select rebroadcast_count from " + attemptTable(collection) + " where batch_id = ?", Integer.class, coolingDown));
    }

    private List<Observation> observations(boolean collection, int limit) {
        return collection ? collections.listUnknownAttempts("ETH", "sepolia", limit).stream()
                .map(row -> new Observation(row.tenantId(), row.batchId(), row.signedTxCiphertext())).toList()
                : withdrawals.listUnknownAttempts("ETH", "sepolia", limit).stream()
                .map(row -> new Observation(row.tenantId(), row.batchId(), row.signedTxCiphertext())).toList();
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, 'Recovery query test')", id, "recovery-" + id);
        return id;
    }

    private UUID batch(boolean collection, UUID tenant, String chain, String network, String status, int order) {
        UUID id = UUID.randomUUID();
        String table = collection ? "evm_collection_batch" : "evm_withdrawal_batch";
        String hash = "0x" + id.toString().replace("-", "").repeat(2);
        String address = "0x" + "1".repeat(40);
        jdbc.update("insert into " + table + """
                (id, tenant_id, chain, network, asset_symbol, token_contract, token_decimals, hot_wallet,
                 relayer_address, delegate_version, batch_hash, status, item_count)
                values (?, ?, ?, ?, 'ETH', ?, 18, ?, ?, 1, ?, ?, 1)
                """, id, tenant, chain, network, address, address, address, hash, status);
        jdbc.update("insert into " + attemptTable(collection) + """
                (id, tenant_id, batch_id, attempt_no, relayer_nonce, tx_hash, max_fee_per_gas,
                 max_priority_fee_per_gas, gas_limit, calldata_hash, signed_tx_ciphertext,
                 encryption_key_version, status, created_at)
                values (?, ?, ?, 1, 0, ?, 2, 1, 21000, ?, ?, 'test-key', 'UNKNOWN', ?)
                """, UUID.randomUUID(), tenant, id, hash, hash, "cipher-" + id,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(order)));
        return id;
    }

    private static String attemptTable(boolean collection) {
        return collection ? "evm_collection_batch_attempt" : "evm_withdrawal_batch_attempt";
    }

    private record Observation(UUID tenantId, UUID batchId, String ciphertext) { }
}
