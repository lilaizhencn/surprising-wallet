package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.dao.DepositCreditObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyDepositProjectionIntegrationTest {
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = CustodyIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CustodyIntegrationDatabase.reset(dataSource);
    }

    @Test
    void confirmedDepositProjectsDisabledAddressAndSubjectAtomically() {
        ObjectMapper objectMapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        CustodyRepository custodyRepository = new CustodyRepository(jdbc);
        CustodyDepositCreditObserver observer =
                new CustodyDepositCreditObserver(
                        jdbc, objectMapper, custodyRepository,
                        new CustodyTenantChainRepository(jdbc));
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("custodyDepositCreditObserver", observer));
        ChainJdbcRepository chainRepository = new ChainJdbcRepository(
                jdbc, beans.getBeanProvider(DepositCreditObserver.class));

        transactions.executeWithoutResult(status -> {
            DepositFixture fixture = createFixture("user_10086", "DISABLED");
            DepositEvent event = event(fixture.address(), new BigDecimal("5.25"));
            assertTrue(chainRepository.recordAndCreditDeposit(event, 0L, 12, fixture.accountId()));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from custody_address
                     where id = ? and status <> 'DISABLED'
                    """, Integer.class, fixture.custodyAddressId()));
            assertEquals(0, new BigDecimal("5.25").compareTo(jdbc.queryForObject("""
                    select total_balance from ledger_balance
                     where chain = 'ETH' and asset_symbol = 'ETH' and account_id = ?
                    """, BigDecimal.class, fixture.accountId())));
            assertEquals("user_10086", jdbc.queryForObject("""
                    select c.subject
                      from custody_deposit d
                      join custody_address c on c.id = d.custody_address_id
                     where d.tenant_id = ? and d.tx_hash = ?
                    """, String.class, fixture.tenantId(), event.txId()));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from custody_ledger_entry
                     where tenant_id = ? and direction = 'CREDIT'
                       and reference_id = ?
                    """, Integer.class, fixture.tenantId(), "ETH:" + event.txId() + ":0"));
            assertEquals("user_10086", jdbc.queryForObject("""
                    select payload #>> '{data,subject}'
                      from custody_event
                     where tenant_id = ? and event_type = 'DEPOSIT.CONFIRMED'
                       and aggregate_id = ?
                    """, String.class, fixture.tenantId(), "ETH:" + event.txId() + ":0"));
            assertEquals("PUBLISHED", jdbc.queryForObject("""
                    select status from custody_event
                     where tenant_id = ? and event_type = 'DEPOSIT.CONFIRMED'
                       and aggregate_id = ?
                    """, String.class, fixture.tenantId(), "ETH:" + event.txId() + ":0"));
            status.setRollbackOnly();
        });
    }

    @Test
    void observerFailureRollsBackDepositAndBalance() {
        DepositCreditObserver failingObserver = (ignoredEvent, ignoredIndex, ignoredAccount) -> {
            throw new IllegalStateException("projection failure");
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("failingObserver", failingObserver));
        ChainJdbcRepository chainRepository = new ChainJdbcRepository(
                jdbc, beans.getBeanProvider(DepositCreditObserver.class));
        String[] transactionId = new String[1];
        String[] accountId = new String[1];

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(
                status -> {
                    DepositFixture fixture = createFixture("rollback-user", "ACTIVE");
                    DepositEvent event = event(fixture.address(), new BigDecimal("2.75"));
                    transactionId[0] = event.txId();
                    accountId[0] = fixture.accountId();
                    chainRepository.recordAndCreditDeposit(event, 0L, 12, fixture.accountId());
                }));

        assertEquals(0, jdbc.queryForObject(
                "select count(*) from deposit_record where chain = 'ETH' and tx_hash = ?",
                Integer.class, transactionId[0]));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ledger_balance
                 where chain = 'ETH' and asset_symbol = 'ETH' and account_id = ?
                """, Integer.class, accountId[0]));
    }

    @Test
    void pendingDepositIsAttributedToTenantBeforeCredit() {
        transactions.executeWithoutResult(status -> {
            DepositFixture fixture = createFixture("pending-user", "ACTIVE");
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
            DepositEvent event = new DepositEvent(
                    ChainType.ETH, "ETH",
                    "0x" + UUID.randomUUID().toString().replace("-", ""),
                    "0x2222222222222222222222222222222222222222",
                    fixture.address(), new BigDecimal("1.25"),
                    123_456L, 1, null, "{\"integration\":true}");

            assertFalse(repository.recordAndCreditDeposit(
                    event, 0L, 12, fixture.accountId()));
            assertEquals(fixture.tenantId(), jdbc.queryForObject("""
                    select tenant_id from deposit_record
                     where chain = 'ETH' and tx_hash = ? and log_index = 0
                    """, UUID.class, event.txId()));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from custody_deposit
                     where tenant_id = ? and tx_hash = ?
                    """, Integer.class, fixture.tenantId(), event.txId()));
            status.setRollbackOnly();
        });
    }

    @Test
    void tenantSubjectMappingIsStableAndTenantIsolated() {
        transactions.executeWithoutResult(status -> {
            CustodyRepository repository = new CustodyRepository(jdbc);
            UUID firstTenant = insertTenant("subject-first");
            UUID secondTenant = insertTenant("subject-second");

            int first = repository.resolveDerivationSubject(firstTenant, "user_10086");
            int repeated = repository.resolveDerivationSubject(firstTenant, "user_10086");
            int isolated = repository.resolveDerivationSubject(secondTenant, "user_10086");

            assertEquals(first, repeated);
            assertTrue(first > 0);
            assertTrue(isolated > 0);
            assertTrue(first != isolated);
            status.setRollbackOnly();
        });
    }

    @Test
    void webhookDeliveryIsAutomaticOnlyForApiCreatedAddresses() {
        ObjectMapper objectMapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        CustodyRepository custodyRepository = new CustodyRepository(jdbc);
        CustodyDepositCreditObserver observer =
                new CustodyDepositCreditObserver(
                        jdbc, objectMapper, custodyRepository,
                        new CustodyTenantChainRepository(jdbc));
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("custodyDepositCreditObserver", observer));
        ChainJdbcRepository chainRepository = new ChainJdbcRepository(
                jdbc, beans.getBeanProvider(DepositCreditObserver.class));

        transactions.executeWithoutResult(status -> {
            DepositFixture apiAddress = createFixture("api-user", "ACTIVE", "API");
            insertActiveWebhook(apiAddress.tenantId());
            DepositEvent apiEvent = event(apiAddress.address(), new BigDecimal("1.25"));
            assertTrue(chainRepository.recordAndCreditDeposit(
                    apiEvent, 0L, 12, apiAddress.accountId()));
            assertEquals(1, deliveryCount(apiAddress.tenantId()));

            DepositFixture consoleAddress = createFixture("console-user", "ACTIVE", "CONSOLE");
            insertActiveWebhook(consoleAddress.tenantId());
            DepositEvent consoleEvent = event(consoleAddress.address(), new BigDecimal("2.50"));
            assertTrue(chainRepository.recordAndCreditDeposit(
                    consoleEvent, 0L, 12, consoleAddress.accountId()));
            assertEquals(0, deliveryCount(consoleAddress.tenantId()));
            status.setRollbackOnly();
        });
    }

    private DepositFixture createFixture(String subjectValue, String status) {
        return createFixture(subjectValue, status, "API");
    }

    private DepositFixture createFixture(String subjectValue, String status, String source) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID custodyAddressId = UUID.randomUUID();
        int namespace = jdbc.queryForObject(
                "select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
        int subject = jdbc.queryForObject(
                "select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        String address = "0x" + suffix + suffix.substring(0, 8);
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, ?, 'Custody deposit integration test', ?)
                """, tenantId, "deposit-it-" + suffix.substring(0, 16), namespace);
        UUID administratorId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, 'Deposit administrator', 'test-only-hash',
                        'TENANT_ADMIN', 'ACTIVE')
                """, administratorId, tenantId, "deposit-" + suffix.substring(0, 12) + "@example.test");
        new CustodyTenantChainRepository(jdbc).setStatus(
                tenantId, "ETH", "ACTIVE", administratorId);
        Long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, derivation_path, wallet_role, enabled)
                values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, 'DEPOSIT', true)
                returning id
                """, Long.class, tenantId, address, Integer.toUnsignedLong(subject), namespace,
                address, "m/44'/60'/" + namespace + "'/" + subject + "/0");
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, status, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'integration', ?, ?, ?, ?, ?, 0)
                """, custodyAddressId, tenantId, chainAddressId, address,
                subjectValue, source, status, subject);
        return new DepositFixture(tenantId, custodyAddressId, address, address);
    }

    private void insertActiveWebhook(UUID tenantId) {
        jdbc.update("""
                insert into custody_webhook_endpoint(
                    id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                values (?, ?, 'Automatic events', 'https://example.com/hooks',
                        'ciphertext', 'ACTIVE', now())
                """, UUID.randomUUID(), tenantId);
    }

    private int deliveryCount(UUID tenantId) {
        return jdbc.queryForObject(
                "select count(*) from custody_webhook_delivery where tenant_id = ?",
                Integer.class, tenantId);
    }

    private UUID insertTenant(String prefix) {
        UUID tenantId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                insert into custody_tenant(id, slug, name)
                values (?, ?, 'Subject allocation integration test')
                """, tenantId, prefix + "-" + suffix);
        return tenantId;
    }

    private static DepositEvent event(String address, BigDecimal amount) {
        return new DepositEvent(
                ChainType.ETH,
                "ETH",
                "0x" + UUID.randomUUID().toString().replace("-", ""),
                "0x2222222222222222222222222222222222222222",
                address,
                amount,
                123_456L,
                12,
                null,
                "{\"integration\":true}");
    }

    private record DepositFixture(
            UUID tenantId,
            UUID custodyAddressId,
            String address,
            String accountId
    ) {
    }
}
