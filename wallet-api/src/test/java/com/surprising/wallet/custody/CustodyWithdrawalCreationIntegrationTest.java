package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.RuntimeTestSupport;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyWithdrawalCreationIntegrationTest {
    private CountingJdbc jdbc;
    private AnnotationConfigApplicationContext context;
    private CustodyWithdrawalService service;
    private final UUID tenant = UUID.randomUUID();
    private final UUID custodyId = UUID.randomUUID();
    private final String address = "0x" + "12".repeat(20);

    @BeforeEach void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new CountingJdbc(dataSource);
        jdbc.update("update chain_profile set enabled=true, withdraw_enabled=true where chain='ETH' and network='sepolia'");
        jdbc.update("insert into custody_tenant(id,slug,name,derivation_namespace) values (?,?,'Withdrawal fixture',nextval('custody_derivation_namespace_seq'))",
                tenant, "withdraw-test-" + tenant);
        long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,
                    address,owner_address,derivation_path,wallet_role,enabled)
                values (?,'ETH','ETH','withdraw-account',1,1,0,?,?,'m/44/60/1/1/0','DEPOSIT',true) returning id
                """, Long.class, tenant, address, address);
        jdbc.update("""
                insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,
                    status,derivation_subject,derivation_child)
                values (?,?,?,'ETH','sepolia',?,'withdraw-test','API','ACTIVE',1,0)
                """, custodyId, tenant, chainAddressId, address);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','withdraw-account',5,0,5)", tenant);
        var runtime = mock(BlockchainRuntimeService.class);
        when(runtime.checkAddress(eq("ETH"), anyString())).thenReturn(true);
        var planning = mock(CollectionPlanningService.class);
        when(planning.destination(tenant, "ETH")).thenReturn(Optional.empty());
        var properties = new CustodySecurityProperties(); properties.setSecretMasterKey("33".repeat(32));
        var mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        var execution = new CustodyWithdrawalExecutionService(new WithdrawalOrderRepository(jdbc), planning,
                RuntimeTestSupport.database(jdbc), runtime, new ChainAssetRepository(jdbc),
                new ChainProfileRepository(jdbc), new TokenConfigRepository(jdbc), new LedgerBalanceRepository(jdbc));
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(CustodyWithdrawalService.class, () -> new CustodyWithdrawalService(execution,
                mock(CustodyGasService.class), mock(CustodyTenantChainService.class), new CustodyCryptoService(properties),
                mapper, CustodyTestSupport.events(jdbc), new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyTenantTableRepository(jdbc), new ChainAddressRepository(jdbc), new CustodyReorgDeficitRepository(jdbc),
                new CustodyIdempotencyRepository(jdbc), new CustodyWithdrawalRepository(jdbc), new CustodyAuditRepository(jdbc)));
        context.refresh(); service = context.getBean(CustodyWithdrawalService.class);
        jdbc.selects.clear();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void creationReadsEachAddressOnceAndReturnsInsertedOrderIdWithoutRequery() {
        var created = create("127.0.0.1");
        assertEquals(1, jdbc.selects.getOrDefault("custody_address", 0));
        assertEquals(1, jdbc.selects.getOrDefault("chain_address", 0));
        assertEquals(0, jdbc.selects.getOrDefault("withdrawal_order", 0));
        var replay = create("127.0.0.1");
        assertEquals(created.id(), replay.id()); assertEquals(created.orderNo(), replay.orderNo());
        assertEquals(1, count("withdrawal_order")); assertEquals(1, count("custody_withdrawal"));
        assertEquals(0, new BigDecimal("4").compareTo(balance("available_balance")));
        assertEquals(0, BigDecimal.ONE.compareTo(balance("locked_balance")));
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_event where tenant_id=? and event_type='WITHDRAWAL.CREATED'", Integer.class, tenant));
    }

    @Test void auditFailureRollsBackOrderFreezeCustodyEventAndIdempotencyTogether() {
        assertThrows(RuntimeException.class, () -> create("invalid-ip"));
        for (String table : List.of("withdrawal_order", "custody_withdrawal", "custody_event", "custody_idempotency_key")) assertEquals(0, count(table), table);
        assertEquals(0, new BigDecimal("5").compareTo(balance("available_balance")));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance("locked_balance")));
    }

    @Test void disabledInactiveAndForeignAddressGuardsRemainEffective() {
        jdbc.update("update chain_address set enabled=false where tenant_id=?", tenant);
        assertThrows(IllegalArgumentException.class, () -> create("127.0.0.1"));
        jdbc.update("update chain_address set enabled=true where tenant_id=?", tenant);
        jdbc.update("update custody_address set status='DISABLED' where id=?", custodyId);
        assertThrows(IllegalArgumentException.class, () -> create("127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> service.create(CustodyTestSupport.tenantAdmin(UUID.randomUUID()),
                command(), "API", "foreign-request", "127.0.0.1"));
        assertEquals(0, count("withdrawal_order")); assertEquals(0, count("custody_idempotency_key"));
        assertEquals(0, new BigDecimal("5").compareTo(balance("available_balance")));
    }

    private CustodyWithdrawalService.WithdrawalView create(String sourceIp) {
        return service.create(CustodyTestSupport.tenantAdmin(tenant), command(), "API", "withdrawal-test-request", sourceIp);
    }
    private CustodyWithdrawalService.CreateWithdrawalCommand command() {
        return new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId, "ETH", "ETH", "0x" + "34".repeat(20), "1", "external-test", true);
    }
    private BigDecimal balance(String column) { return jdbc.queryForObject("select " + column + " from ledger_balance where tenant_id=?", BigDecimal.class, tenant); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }

    private static final class CountingJdbc extends JdbcTemplate {
        private final Map<String, Integer> selects = new HashMap<>();
        CountingJdbc(DataSource dataSource) { super(dataSource); }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            String normalized = sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (normalized.startsWith("select ")) for (String table : List.of("custody_address", "chain_address", "withdrawal_order")) {
                if (normalized.contains(" from " + table + " ")) selects.merge(table, 1, Integer::sum);
            }
            return super.query(sql, mapper, args);
        }
    }
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }
}
