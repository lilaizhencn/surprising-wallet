package com.surprising.wallet.custody;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.EvmBatchOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** UUID batch identity and tenant ownership are preserved through the real table queries. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class EvmBatchOwnershipIntegrationTest {
    private static final String ADDRESS = "0x" + "1".repeat(40);
    private JdbcTemplate jdbc;
    private EvmBatchOwnershipService ownership;
    private UUID tenant;
    private UUID otherTenant;
    private UUID custodyAddress;

    @BeforeEach void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        jdbc = new JdbcTemplate(source);
        ownership = new EvmBatchOwnershipService(new EvmWithdrawalBatchItemRepository(jdbc),
                new EvmWithdrawalBatchRepository(jdbc), new EvmCollectionBatchItemRepository(jdbc),
                new EvmCollectionBatchRepository(jdbc));
        tenant = tenant();
        otherTenant = tenant();
        long chainAddress = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, owner_address, derivation_path, wallet_role, enabled)
                values (?, 'ETH', 'ETH', 'ownership-test', 17, 0, 0, ?, ?, 'test-path', 'DEPOSIT', true)
                returning id
                """, Long.class, tenant, ADDRESS, ADDRESS);
        custodyAddress = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'sepolia', ?, 'ownership-test', 'API', 17, 0)
                """, custodyAddress, tenant, chainAddress, ADDRESS);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void onlySubmittedItemsInOwnedPendingUuidBatchesSuppressSingleTransactionConfirmation(boolean collection) {
        UUID batch = UUID.randomUUID();
        String batchTable = collection ? "evm_collection_batch" : "evm_withdrawal_batch";
        String itemTable = collection ? "evm_collection_batch_item" : "evm_withdrawal_batch_item";
        String hash = "0x" + batch.toString().replace("-", "").repeat(2);
        jdbc.update("insert into " + batchTable + """
                (id, tenant_id, chain, network, asset_symbol, token_contract, token_decimals, hot_wallet,
                 relayer_address, delegate_version, batch_hash, status, item_count)
                values (?, ?, 'ETH', 'sepolia', 'ETH', ?, 18, ?, ?, 1, ?, 'SUBMITTED', 1)
                """, batch, tenant, ADDRESS, ADDRESS, ADDRESS, hash);
        long recordId = collection ? collectionItem(batch) : withdrawalItem(batch, hash);

        for (String status : List.of("SUBMITTED", "CONFIRMING", "BROADCAST_UNKNOWN")) {
            jdbc.update("update " + batchTable + " set status = ? where id = ?", status, batch);
            assertTrue(pending(collection, tenant, recordId), status);
            assertFalse(pending(collection, otherTenant, recordId), "foreign tenant must not own the same record ID");
            assertFalse(pending(collection, tenant, recordId + 100_000), "unrelated record must not be suppressed");
        }
        for (String status : List.of("CONFIRMED", "FAILED", "PARTIAL_FAILED")) {
            jdbc.update("update " + batchTable + " set status = ? where id = ?", status, batch);
            assertFalse(pending(collection, tenant, recordId), status);
        }
        jdbc.update("update " + batchTable + " set status = 'SUBMITTED' where id = ?", batch);
        jdbc.update("update " + itemTable + " set status = 'CONFIRMED' where batch_id = ?", batch);
        assertFalse(pending(collection, tenant, recordId), "completed item must not suppress single confirmation");
    }

    private long collectionItem(UUID batch) {
        long id = jdbc.queryForObject("""
                insert into collection_record(tenant_id, custody_address_id, collection_no, chain, asset_symbol,
                    from_address, to_address, amount, status)
                values (?, ?, 'ownership-collection', 'ETH', 'ETH', ?, ?, 1, 'SENT') returning id
                """, Long.class, tenant, custodyAddress, ADDRESS, ADDRESS);
        jdbc.update("""
                insert into evm_collection_batch_item(id, tenant_id, batch_id, item_index, collection_record_id,
                    custody_address_id, authority_address, token_contract, recipient, requested_amount_atomic,
                    operation_nonce, signature_deadline, call_gas_limit, status)
                values (?, ?, ?, 0, ?, ?, ?, ?, ?, 1, 0, now() + interval '1 hour', 180000, 'SUBMITTED')
                """, UUID.randomUUID(), tenant, batch, id, custodyAddress, ADDRESS, ADDRESS, ADDRESS);
        return id;
    }

    private long withdrawalItem(UUID batch, String hash) {
        long id = jdbc.queryForObject("""
                insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                    to_address, amount, status)
                values (?, 'ownership-withdrawal', 17, 'ETH', 'ETH', ?, ?, 1, 'SENT') returning id
                """, Long.class, tenant, ADDRESS, ADDRESS);
        UUID custodyWithdrawal = UUID.randomUUID();
        jdbc.update("""
                insert into custody_withdrawal(id, tenant_id, custody_address_id, withdrawal_order_id,
                    order_no, chain, asset_symbol, to_address, amount, status, created_by_type)
                values (?, ?, ?, ?, 'ownership-withdrawal', 'ETH', 'ETH', ?, 1, 'SENT', 'CONSOLE')
                """, custodyWithdrawal, tenant, custodyAddress, id, ADDRESS);
        jdbc.update("""
                insert into evm_withdrawal_batch_item(id, tenant_id, batch_id, item_index, withdrawal_order_id,
                    custody_withdrawal_id, withdrawal_id_hash, recipient, token_contract,
                    requested_amount_atomic, call_gas_limit, status)
                values (?, ?, ?, 0, ?, ?, ?, ?, ?, 1, 120000, 'SUBMITTED')
                """, UUID.randomUUID(), tenant, batch, id, custodyWithdrawal, hash, ADDRESS, ADDRESS);
        return id;
    }

    private boolean pending(boolean collection, UUID owner, long id) {
        return collection ? ownership.collectionPending(owner, id) : ownership.withdrawalPending(owner, id);
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, 'Batch ownership test')", id, "ownership-" + id);
        return id;
    }
}
