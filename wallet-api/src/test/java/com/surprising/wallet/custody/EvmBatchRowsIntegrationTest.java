package com.surprising.wallet.custody;

import com.surprising.wallet.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Physical row reads preserve ownership, nullable authorization and full-width atomic numbers. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class EvmBatchRowsIntegrationTest {
    private static final String ADDRESS = "0x" + "12".repeat(20);
    private static final String RECIPIENT = "0x" + "34".repeat(20);
    private static final String HASH = "0x" + "56".repeat(32);
    private JdbcTemplate jdbc;
    private UUID tenant;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        tenant = UUID.randomUUID();
        new CustodyTenantTableRepository(jdbc).insert(tenant, "typed-batch-" + tenant, "Typed batch rows");
    }

    @Test
    void withdrawalBatchQueriesShareTheirRowMappingWithoutNarrowingOrLosingNullAuthorization() {
        UUID id = batch(false);
        BigInteger nonce = BigInteger.ONE.shiftLeft(200).add(BigInteger.valueOf(137));
        jdbc.update("""
                update evm_withdrawal_batch set status='SUBMITTED', canonical_tx_hash=?,
                    authorization_included=true, authorization_nonce=?, operation_nonce=?,
                    error_code='ROW_TEST', error_message='owned row metadata' where id=?
                """, HASH, nonce.subtract(BigInteger.ONE), nonce, id);
        var repository = new EvmWithdrawalBatchRepository(jdbc);
        var row = repository.find(tenant, id).orElseThrow();
        assertEquals(row, repository.listPending("ETH", "sepolia", 0).getFirst());
        assertEquals(tenant, row.tenantId());
        assertEquals(id, row.id());
        assertEquals(nonce, row.operationNonce());
        assertEquals(nonce.subtract(BigInteger.ONE), row.authorizationNonce());
        assertTrue(row.authorizationIncluded());
        assertEquals("ROW_TEST", row.errorCode());
        assertEquals("owned row metadata", row.errorMessage());
        assertEquals(ADDRESS, row.hotWallet());
        assertEquals(HASH, row.canonicalTxHash());
        assertEquals(1, row.delegateVersion());
        assertTrue(repository.find(UUID.randomUUID(), id).isEmpty());
        assertTrue(repository.listPending("ETH", "absent", 1).isEmpty());
        jdbc.update("update evm_withdrawal_batch set authorization_included=false, authorization_nonce=null where id=?", id);
        assertNull(repository.find(tenant, id).orElseThrow().authorizationNonce());
        assertEquals(nonce, repository.find(tenant, id).orElseThrow().operationNonce());
    }

    @Test
    void collectionBatchQueriesKeepTheirOwnedIdentityAndOriginalConfigurationVersion() {
        UUID id = batch(true);
        jdbc.update("update evm_collection_batch set status='SUBMITTED', canonical_tx_hash=? where id=?", HASH, id);
        var repository = new EvmCollectionBatchRepository(jdbc);
        var row = repository.find(tenant, id).orElseThrow();
        assertEquals(row, repository.listPending("ETH", "sepolia", 1).getFirst());
        assertEquals(tenant, row.tenantId());
        assertEquals("ETH", row.chain());
        assertEquals("sepolia", row.network());
        assertEquals(1, row.delegateVersion());
        assertEquals(18, row.tokenDecimals());
        assertEquals(HASH, row.batchHash());
        assertTrue(repository.find(UUID.randomUUID(), id).isEmpty());
        assertTrue(repository.listPending("ETH", "absent", 1).isEmpty());
    }

    @Test
    void withdrawalItemAndOrderReadsKeepExactAmountsAndReturnOnlyOwnedSubmittedBatchKeys() {
        var owned = address();
        BigInteger atomic = BigInteger.ONE.shiftLeft(180).add(BigInteger.valueOf(137));
        BigDecimal amount = new BigDecimal(atomic).movePointLeft(18);
        var orders = new WithdrawalOrderRepository(jdbc);
        String number = "typed-" + UUID.randomUUID();
        orders.createForTenant(tenant, number, owned.subject(), "ETH", "ETH", ADDRESS, "typed-account", RECIPIENT, amount, BigDecimal.ZERO);
        jdbc.update("update withdrawal_order set status='FROZEN' where tenant_id=? and order_no=?", tenant, number);
        var order = orders.find("ETH", number, tenant).orElseThrow();
        assertEquals(order, orders.findById(tenant, order.getId()).orElseThrow());
        transaction.executeWithoutResult(status -> assertEquals(order, orders.listClaimable("ETH", 1).getFirst()));
        assertTrue(orders.findById(UUID.randomUUID(), order.getId()).isEmpty());
        UUID custodyWithdrawal = UUID.randomUUID();
        var custody = new CustodyWithdrawalRepository(jdbc);
        custody.insert(custodyWithdrawal, tenant, owned.custodyId(), order.getId(), number, "typed-reference",
                "typed-key", "ETH", "ETH", RECIPIENT, amount, BigDecimal.ZERO, "FROZEN", "API_KEY", "row-test");
        assertEquals(custodyWithdrawal, custody.listByOrder(tenant, order.getId()).getFirst().id());
        assertTrue(custody.listByOrder(UUID.randomUUID(), order.getId()).isEmpty());
        UUID parent = batch(false);
        var items = new EvmWithdrawalBatchItemRepository(jdbc);
        items.insert(UUID.randomUUID(), tenant, parent, 0, order.getId(), custodyWithdrawal, HASH, RECIPIENT, ADDRESS, atomic);
        var item = items.listByBatch(tenant, parent).getFirst();
        assertEquals(item, items.listCreated(tenant, parent).getFirst());
        assertEquals(atomic, item.requestedAmountAtomic());
        assertEquals(tenant, item.tenantId());
        assertEquals(parent, item.batchId());
        assertEquals(custodyWithdrawal, item.custodyWithdrawalId());
        assertTrue(items.listByBatch(UUID.randomUUID(), parent).isEmpty());
        assertTrue(items.listSubmittedByWithdrawal(tenant, order.getId()).isEmpty());
        items.markSigned(tenant, parent, 1);
        items.markSubmitted(tenant, parent);
        assertEquals(java.util.List.of(parent), items.listSubmittedByWithdrawal(tenant, order.getId()));
        assertTrue(items.listSubmittedByWithdrawal(UUID.randomUUID(), order.getId()).isEmpty());
    }

    @Test
    void collectionItemCompletionPreservesFullOperationNonceAndReleaseIdentity() {
        var owned = address();
        var records = new CollectionRecordRepository(jdbc);
        records.create(tenant, owned.custodyId(), "typed-collection", "ETH", "ETH", ADDRESS, RECIPIENT,
                new BigDecimal("0.123456789012345678"), BigDecimal.ZERO, null);
        long recordId = jdbc.queryForObject("select id from collection_record where tenant_id=? and collection_no='typed-collection'", Long.class, tenant);
        var record = records.findById(tenant, recordId).orElseThrow();
        transaction.executeWithoutResult(status -> assertEquals(record, records.listClaimable("ETH", 1).getFirst()));
        assertTrue(records.findById(UUID.randomUUID(), recordId).isEmpty());
        UUID parent = batch(true);
        var items = new EvmCollectionBatchItemRepository(jdbc);
        items.insert(UUID.randomUUID(), tenant, parent, 0, recordId, owned.custodyId(), ADDRESS, ADDRESS,
                RECIPIENT, BigInteger.valueOf(123456789012345678L), Timestamp.from(Instant.now().plusSeconds(600)));
        assertEquals(new EvmCollectionBatchItemRepository.ReleaseItem(0, recordId), items.listForRelease(tenant, parent).getFirst());
        BigInteger nonce = BigInteger.ONE.shiftLeft(200).add(BigInteger.valueOf(137));
        items.markSigned(tenant, parent, 0, ADDRESS, true, BigInteger.valueOf(7), nonce,
                Timestamp.from(Instant.now().plusSeconds(600)), 180000);
        var completion = items.findForCompletion(tenant, parent, 0).orElseThrow();
        assertEquals(nonce, completion.operationNonce());
        assertEquals(owned.custodyId(), completion.custodyAddressId());
        assertEquals(recordId, completion.collectionRecordId());
        assertTrue(completion.authorizationIncluded());
        assertTrue(items.findForCompletion(UUID.randomUUID(), parent, 0).isEmpty());
        assertTrue(items.listForRelease(tenant, parent).isEmpty());
        items.markSubmitted(tenant, parent);
        assertEquals(java.util.List.of(parent), items.listSubmittedByCollection(tenant, recordId));
        assertTrue(items.listSubmittedByCollection(UUID.randomUUID(), recordId).isEmpty());
    }

    private UUID batch(boolean collection) {
        UUID id = UUID.randomUUID();
        if (collection) new EvmCollectionBatchRepository(jdbc).insert(id, tenant, "ETH", "sepolia", "ETH", ADDRESS,
                18, ADDRESS, RECIPIENT, 1, HASH, 1);
        else new EvmWithdrawalBatchRepository(jdbc).insert(id, tenant, "ETH", "sepolia", "ETH", ADDRESS,
                18, ADDRESS, RECIPIENT, 1, HASH, 1);
        return id;
    }

    private OwnedAddress address() {
        int namespace = jdbc.queryForObject("select derivation_namespace from custody_tenant where id=?", Integer.class, tenant);
        int subject = jdbc.queryForObject("select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        long chainAddress = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, derivation_path, wallet_role, enabled)
                values (?, 'ETH', 'ETH', 'typed-account', ?, ?, 0, ?, 'typed-test', 'DEPOSIT', true) returning id
                """, Long.class, tenant, Integer.toUnsignedLong(subject), namespace, ADDRESS);
        UUID custodyAddress = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'sepolia', ?, 'typed-subject', 'API', ?, 0)
                """, custodyAddress, tenant, chainAddress, ADDRESS, subject);
        return new OwnedAddress(custodyAddress, subject);
    }

    private record OwnedAddress(UUID custodyId, long subject) { }
}
