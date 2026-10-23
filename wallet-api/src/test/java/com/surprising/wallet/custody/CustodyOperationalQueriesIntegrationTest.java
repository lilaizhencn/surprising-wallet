package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.service.CustodyAddressService;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.service.CustodyTenantChainService;
import com.surprising.wallet.testutil.CustodyTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Complete tenant-scoped operational queries on local PG18, including records beyond the former caps. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyOperationalQueriesIntegrationTest {
    private JdbcTemplate jdbc;
    private CustodyAddressService addresses;
    private CustodyGasService gas;

    @BeforeEach void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var repository = CustodyTestSupport.repository(jdbc);
        addresses = CustodyTestSupport.addresses(jdbc, repository, mock(BlockchainRuntimeService.class),
                mock(CustodyTenantChainService.class));
        gas = CustodyTestSupport.gas(jdbc, repository, addresses, mock(BlockchainRuntimeService.class));
    }

    @Test void addressTotalsAndDeepPagesUseTheCompleteDataset() {
        var tenant = tenant();
        var ids = seedAddresses(tenant, "ETH", 620);
        seedGas(tenant, "ETH");
        seedAddresses(tenant(), "ETH", 9);
        var principal = principal(tenant);
        assertAll(
                () -> assertEquals(620, addresses.page(principal, null, null, null, "", 20, 0).total()),
                () -> assertEquals(ids.subList(600, 620), addresses.list(principal, null, null, null, "", 20, 600)
                        .stream().map(CustodyAddressService.AddressView::id).toList()));
    }

    @Test void addressSearchIsLiteralCaseInsensitiveAndCanReachOldRecords() {
        var tenant = tenant();
        var ids = seedAddresses(tenant, "ETH", 620);
        var target = ids.getLast();
        jdbc.update("update custody_address set label = 'Label LITERAL_%_TARGET' where id = ?", target);
        var foreign = seedAddresses(tenant(), "ETH", 1).getFirst();
        jdbc.update("update custody_address set label = 'Label LITERAL_%_TARGET' where id = ?", foreign);
        var page = addresses.page(principal(tenant), "eth", "api", "active", "literal_%_target", 20, 0);
        assertEquals(1, page.total());
        assertEquals(List.of(target), page.items().stream().map(CustodyAddressService.AddressView::id).toList());
    }

    @Test void addressFiltersAndCountsExcludeGasAddressesEvenWhenTheyAreDisabled() {
        var tenant = tenant();
        var ids = seedAddresses(tenant, "ETH", 620);
        for (int index = 0; index < ids.size(); index++) {
            if (index % 2 == 0) jdbc.update("update custody_address set source = 'CONSOLE' where id = ?", ids.get(index));
            if (index % 3 == 0) jdbc.update("update custody_address set status = 'DISABLED' where id = ?", ids.get(index));
        }
        var gasAccount = seedGas(tenant, "ETH");
        jdbc.update("update custody_gas_account set status = 'DISABLED' where id = ?", gasAccount.id());
        var page = addresses.page(principal(tenant), "ETH", "CONSOLE", "DISABLED", "", 20, 80);
        assertEquals(104, page.total());
        assertEquals(20, page.items().size());
        assertTrue(page.items().stream().allMatch(row -> "CONSOLE".equals(row.source()) && "DISABLED".equals(row.status())));
        assertEquals(620, addresses.page(principal(tenant), null, null, null, "", 20, 0).total());
    }

    @Test void metadataUpdateRetainsTheExistingMemo() {
        var tenant = tenant();
        var id = seedAddresses(tenant, "ETH", 1).getFirst();
        assertEquals("", addresses.list(principal(tenant), "ETH", null, null, "", 20, 0).getFirst().memo());
        jdbc.update("update custody_address set memo = 'destination-tag-42' where id = ?", id);
        var result = addresses.update(principal(tenant), id,
                new CustodyAddressService.UpdateAddressCommand("new label", Map.of("test", true), "ACTIVE"), "127.0.0.1");
        assertEquals("destination-tag-42", result.memo());
        assertEquals("new label", result.label());
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id = ? and action = 'ADDRESS.UPDATE'", Integer.class, tenant));
    }

    @Test void gasTopupsAreFilteredBeforePagination() {
        var tenant = tenant();
        var account = seedGas(tenant, "ETH");
        deposits(tenant, account.addressId(), 25, 1000);
        var customer = seedAddresses(tenant, "ETH", 1).getFirst();
        deposits(tenant, customer, 620, 0);
        var page = gas.topups(principal(tenant), account.id(), 20, 0);
        assertEquals(20, page.size());
        assertTrue(page.stream().allMatch(row -> account.addressId().equals(row.get("custody_address_id"))));
        assertEquals(5, gas.topups(principal(tenant), account.id(), 20, 20).size());
    }

    @Test void gasUsageCanReachRecordsBeyondOtherAccountsAndTheFormer500RowCap() {
        var tenant = tenant();
        var account = seedGas(tenant, "ETH");
        usage(tenant, account, "ETH", 620, 1000);
        usage(tenant, seedGas(tenant, "BTC"), "BTC", 620, 0);
        var page = gas.usage(principal(tenant), account.id(), 20, 600);
        assertEquals(20, page.size());
        assertTrue(page.stream().allMatch(row -> "ETH".equals(row.chain())));
        var payload = new com.surprising.wallet.config.custody.CustodyJacksonConfiguration()
                .custodyObjectMapper().valueToTree(page).get(0);
        assertTrue(payload.has("operationType"));
        assertTrue(payload.path("createdAt").asText().contains("T"));
        assertFalse(payload.has("created_at"));
        assertFalse(payload.has("tenantId"));
        assertEquals(20, gas.usage(principal(tenant), account.id(), 20, 0).size());
    }

    @Test void foreignGasAccountsAndMissingScopesAreRejected() {
        var tenant = tenant();
        var foreign = seedGas(tenant(), "ETH");
        assertThrows(IllegalArgumentException.class, () -> gas.topups(principal(tenant), foreign.id(), 20, 0));
        assertThrows(IllegalArgumentException.class, () -> gas.usage(principal(tenant), foreign.id(), 20, 0));
        var denied = new CustodyPrincipal(CustodyPrincipal.ActorType.API_KEY, UUID.randomUUID(), tenant,
                "query-test", "API_KEY", Set.of());
        assertThrows(CustodyForbiddenException.class, () -> addresses.list(denied, null, null, null, "", 20, 0));
        assertThrows(CustodyForbiddenException.class, () -> gas.usage(denied, foreign.id(), 20, 0));
    }

    private UUID tenant() {
        var id = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, 'Operational query test')", id, "queries-" + id);
        return id;
    }

    private List<UUID> seedAddresses(UUID tenant, String chain, int count) {
        var prefix = UUID.randomUUID().toString();
        var rows = jdbc.queryForList("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz,
                    address_index, address, derivation_path, wallet_role, enabled)
                select ?, ?, ?, '0x' || md5(? || n::text) || '12345678',
                    nextval('custody_derivation_subject_index_seq'), 0, n,
                    '0x' || md5(? || n::text) || '12345678', 'test-path', 'DEPOSIT', true
                  from generate_series(1, ?) n returning id, user_id, address_index, address
                """, tenant, chain, chain, prefix, prefix, count);
        rows.sort(Comparator.comparingLong(row -> ((Number) row.get("address_index")).longValue()));
        var ids = new ArrayList<UUID>();
        var args = new ArrayList<Object[]>();
        for (var row : rows) {
            var id = UUID.randomUUID(); ids.add(id);
            long index = ((Number) row.get("address_index")).longValue();
            args.add(new Object[]{id, tenant, row.get("id"), chain, row.get("address"), "customer-" + prefix + "-" + index,
                    "label-" + index, row.get("user_id"), index, index});
        }
        jdbc.batchUpdate("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, label, source, derivation_subject, address_version, derivation_child, created_at)
                values (?, ?, ?, ?, 'qa', ?, ?, ?, 'API', ?, ?, 0, now() - ? * interval '1 second')
                """, args);
        return ids;
    }

    private GasAccount seedGas(UUID tenant, String chain) {
        var address = seedAddresses(tenant, chain, 1).getFirst();
        jdbc.update("update custody_address set subject = ?, source = 'CONSOLE' where id = ?", "__sw_collection__:" + chain, address);
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network, native_symbol, low_balance_threshold)
                values (?, ?, ?, ?, 'qa', ?, 0.01)
                """, id, tenant, address, chain, chain);
        return new GasAccount(id, address);
    }

    private void deposits(UUID tenant, UUID address, int count, int age) {
        var destination = jdbc.queryForObject("select address from custody_address where id = ?", String.class, address);
        var prefix = UUID.randomUUID().toString();
        var records = jdbc.queryForList("""
                insert into deposit_record(tenant_id, chain, asset_symbol, tx_hash, to_address,
                    amount, block_height, block_hash, confirmations, status, credited, credit_generation, account_id, credited_at)
                select ?, 'ETH', 'ETH', ? || n::text, ?, 1, 100, 'block', 12, 'CONFIRMED', true, 1, ?, now()
                  from generate_series(1, ?) n returning id, tx_hash
                """, tenant, prefix, destination, destination, count);
        var args = new ArrayList<Object[]>();
        for (int index = 0; index < records.size(); index++) {
            var row = records.get(index);
            args.add(new Object[]{UUID.randomUUID(), tenant, address, row.get("id"), row.get("tx_hash"), age + index});
        }
        jdbc.batchUpdate("""
                insert into custody_deposit(id, tenant_id, custody_address_id, deposit_record_id, chain, asset_symbol,
                    tx_hash, amount, status, credited_at, created_at)
                values (?, ?, ?, ?, 'ETH', 'ETH', ?, 1, 'CONFIRMED', now(), now() - ? * interval '1 second')
                """, args);
    }

    private void usage(UUID tenant, GasAccount account, String chain, int count, int age) {
        jdbc.update("""
                insert into custody_gas_usage(id, tenant_id, gas_account_id, operation_type, operation_id,
                    reference_no, chain, native_symbol, reserved_amount, actual_amount, status, created_at)
                select gen_random_uuid(), ?, ?, 'COLLECTION_BATCH', gen_random_uuid(), 'query-test-' || n,
                    ?, ?, 0.01, 0.001, 'SETTLED', now() - (? + n) * interval '1 second'
                  from generate_series(1, ?) n
                """, tenant, account.id(), chain, chain, age, count);
    }

    private static CustodyPrincipal principal(UUID tenant) {
        return new CustodyPrincipal(CustodyPrincipal.ActorType.API_KEY, UUID.randomUUID(), tenant, "query-test", "API_KEY", Set.of("*"));
    }

    private record GasAccount(UUID id, UUID addressId) { }
}
