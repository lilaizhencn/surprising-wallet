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
    private CustodyTenantChainService tenantChains;
    private CustodyGasService gas;
    private boolean rejectGas;
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
                new ChainProfileRepository(jdbc), new TokenConfigRepository(jdbc), new LedgerBalanceRepository(jdbc), CustodyTestSupport.poolFunding(jdbc));
        tenantChains = mock(CustodyTenantChainService.class);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        gas = mock(CustodyGasService.class, invocation -> {
            if (rejectGas && invocation.getMethod().getName().equals("reserveWithdrawal")) throw new IllegalStateException("synthetic Gas reservation failure");
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        context.registerBean(WithdrawalSettlementService.class, () -> new WithdrawalSettlementService(new WithdrawalOrderRepository(jdbc), new LedgerBalanceRepository(jdbc)));
        context.registerBean(CustodyWithdrawalService.class, () -> new CustodyWithdrawalService(execution,
                gas, tenantChains, new CustodyCryptoService(properties),
                mapper, CustodyTestSupport.events(jdbc), new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyTenantTableRepository(jdbc), new ChainAddressRepository(jdbc), new CustodyReorgDeficitRepository(jdbc),
                new CustodyIdempotencyRepository(jdbc), new CustodyWithdrawalRepository(jdbc), new CustodyAuditRepository(jdbc), CustodyTestSupport.poolFunding(jdbc)));
        context.refresh(); service = context.getBean(CustodyWithdrawalService.class);
        jdbc.selects.clear();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void missingReceiptCanNeverTurnAnAcceptedBusinessOrderIntoARefusal() {
        var original=create("127.0.0.1");
        jdbc.update("delete from custody_idempotency_key where tenant_id=? and idempotency_key='withdrawal-test-request'",tenant);
        assertThrows(IllegalStateException.class,()->service.resolve(CustodyTestSupport.tenantAdmin(tenant),command(),"withdrawal-test-request","127.0.0.1"));
        assertEquals(0,count("custody_idempotency_key"));
        assertEquals(1,count("custody_withdrawal"));
        assertNotNull(new WithdrawalOrderRepository(jdbc).find("ETH",original.orderNo(),tenant).orElseThrow());
    }

    @Test void subStorageUnitCannotBeSilentlyRoundedToZeroOrFreezeDifferentPrincipal() {
        jdbc.update("update chain_asset set decimals=36 where chain='ETH' and symbol='ETH'");
        var command=new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId,"ETH","ETH",address,"0.0000000000000000001","too-small",true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
        assertThrows(IllegalArgumentException.class,()->service.create(CustodyTestSupport.tenantAdmin(tenant),command,"API","too-small","127.0.0.1"));
        assertEquals(0,count("withdrawal_order"));assertEquals(0,count("custody_withdrawal"));
        assertEquals(0,new BigDecimal("5").compareTo(balance("available_balance")));
    }

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

    @Test void acceptedRequestReplaysBeforeChangedAddressNetworkAndDeficitGuards() {
        var original=create("127.0.0.1");
        jdbc.update("update withdrawal_order set status='SENT',tx_hash=? where tenant_id=?","0x"+"ab".repeat(32),tenant);
        long deposit=jdbc.queryForObject("insert into deposit_record(tenant_id,chain,asset_symbol,tx_hash,to_address,amount,block_height,account_id) values (?,'ETH','ETH','reorg-fixture',?,1,1,'withdraw-account') returning id",Long.class,tenant,address);
        new CustodyReorgDeficitRepository(jdbc).open(tenant,custodyId,deposit,"ETH","ETH","withdraw-account",BigDecimal.ONE,BigDecimal.ONE);
        jdbc.update("update custody_address set status='DISABLED' where id=?",custodyId);
        doThrow(new com.surprising.wallet.exception.CustodyForbiddenException("network disabled"))
                .when(tenantChains).requireActive(tenant,"ETH");
        var replay=create("127.0.0.1");
        assertEquals(original.id(),replay.id()); assertEquals(1,count("withdrawal_order"));
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
        assertThrows(RuntimeException.class,()->service.create(CustodyTestSupport.tenantAdmin(tenant),command(),"API","different-request","127.0.0.1"));
        assertEquals(1,count("custody_idempotency_key"));
    }

    @Test void withdrawalReceiptSurvivesThirtyDaysAndCleanupWithoutAnotherFreeze() {
        var original=create("127.0.0.1");
        jdbc.update("update custody_idempotency_key set created_at=now()-interval '30 days' where tenant_id=?",tenant);
        assertNull(jdbc.queryForObject("select expires_at from custody_idempotency_key where tenant_id=?",Object.class,tenant));
        new CustodyIdempotencyRepository(jdbc).deleteExpired();
        assertEquals(original.id(),create("127.0.0.1").id());
        assertEquals(1,count("custody_withdrawal"));
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
    }

    @Test void refusalFencesThisIdentityPermanentlyAndCannotOverwriteAnAcceptedReceipt() {
        var principal=CustodyTestSupport.tenantAdmin(tenant);
        var refused=service.resolve(principal,command(),"withdrawal-test-request","127.0.0.1");
        assertEquals("REJECTED",refused.status());
        assertThrows(com.surprising.wallet.exception.CustodyWithdrawalRefusedException.class,()->create("127.0.0.1"));
        assertEquals(0,count("withdrawal_order")); assertEquals(0,balance("locked_balance").signum());
        assertEquals(0,new CustodyIdempotencyRepository(jdbc).complete(tenant,"withdrawal-test-request","WITHDRAWAL.CREATE",202,"{}"));
        assertEquals(422,jdbc.queryForObject("select response_status from custody_idempotency_key where tenant_id=?",Integer.class,tenant));
    }

    @Test void resolutionOfAnAcceptedRequestReturnsItsCommittedIdentity() {
        var original=create("127.0.0.1");
        var resolved=service.resolve(CustodyTestSupport.tenantAdmin(tenant),command(),"withdrawal-test-request","127.0.0.1");
        assertEquals("ACCEPTED",resolved.status()); assertEquals(original.id(),resolved.withdrawal().id());
        assertEquals(1,count("withdrawal_order")); assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
        var changed=new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId,"ETH","ETH",command().toAddress(),"2","external-test",true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
        assertThrows(IllegalStateException.class,()->service.resolve(CustodyTestSupport.tenantAdmin(tenant),changed,"withdrawal-test-request","127.0.0.1"));
        assertEquals(original.id(),create("127.0.0.1").id());
    }

    @Test void racingCreateAndResolveHaveOnlyOneCommittedDecision() throws Exception {
        jdbc.update("update ledger_balance set available_balance=100,total_balance=100 where tenant_id=?",tenant);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        int accepted=0;
        try {
            for(int index=0;index<10;index++) {
                var start=new java.util.concurrent.CountDownLatch(1);
                String key="racing-request-"+index;
                var command=new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId,"ETH","ETH",command().toAddress(),"1","race-"+index,true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
                var creation=executor.submit(()->{
                    start.await();
                    try { service.create(CustodyTestSupport.tenantAdmin(tenant),command,"API",key,"127.0.0.1"); return true; }
                    catch(com.surprising.wallet.exception.CustodyWithdrawalRefusedException refused) { return false; }
                });
                var resolution=executor.submit(()->{start.await(); return service.resolve(CustodyTestSupport.tenantAdmin(tenant),command,key,"127.0.0.1");});
                start.countDown();
                boolean created=creation.get(10,java.util.concurrent.TimeUnit.SECONDS);
                var decision=resolution.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(created ? "ACCEPTED" : "REJECTED",decision.status());
                if(created) accepted++;
            }
        } finally { executor.shutdownNow(); }
        assertEquals(accepted,count("withdrawal_order"));
        assertEquals(10,count("custody_idempotency_key"));
        assertEquals(0,BigDecimal.valueOf(accepted).compareTo(balance("locked_balance")));
    }

    @Test void exactSubjectFilteringHappensBeforeWithdrawalPagination() {
        var original=create("127.0.0.1");
        jdbc.update("update custody_address set subject='another-funding-owner' where id=?",custodyId);
        String foreignAddress="0x"+"56".repeat(20);
        long chainAddress=jdbc.queryForObject("insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,address,owner_address,derivation_path,wallet_role,enabled) values (?,'ETH','ETH','foreign-account',2,1,0,?,?,'m/44/60/2/1/0','DEPOSIT',true) returning id",Long.class,tenant,foreignAddress,foreignAddress);
        UUID foreign=UUID.randomUUID();
        jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) values (?,?,?,'ETH','sepolia',?,'user:420','API','ACTIVE',2,0)",foreign,tenant,chainAddress,foreignAddress);
        // Synthetic history rows only: no signer, scheduler or chain payment runs in this database.
        var orderRepo=new WithdrawalOrderRepository(jdbc); var custodyRepo=new CustodyWithdrawalRepository(jdbc);
        for(int index=0;index<60;index++) {
            String number="foreign-history-"+index;
            long id=orderRepo.createForTenant(tenant,number,2L,"ETH","ETH",foreignAddress,"foreign-account",command().toAddress(),BigDecimal.ONE,BigDecimal.ZERO);
            custodyRepo.insert(UUID.randomUUID(),tenant,foreign,id,number,number,number,"ETH","ETH",command().toAddress(),BigDecimal.ONE,BigDecimal.ZERO,"FROZEN","API_KEY","fixture","user:420","ADDRESS");
        }
        var query=new CustodyTransactionQueryService(new CustodyDepositRepository(jdbc),custodyRepo,new CustodyAddressRepository(jdbc),orderRepo,new CustodyGasUsageRepository(jdbc));
        var rows=query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant),"ETH","ETH","","","user:42","",2,0);
        assertEquals(1,rows.size()); assertEquals(original.id(),rows.getFirst().id());
        assertEquals("user:42",rows.getFirst().subject());
        var exact=query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant),"ETH","ETH","","","","external-test",2,0);
        assertEquals(1,exact.size()); assertEquals(original.id(),exact.getFirst().id());
    }


    @Test void tenantPoolPaysAUserWithNoDepositAddressAndPreservesItsIndependentIdentity() {
        var gasAddress = addPoolSource();
        var created = poolCreate("profit-withdrawal", "4", "user:43");
        assertEquals(gasAddress, created.custodyAddressId());
        assertEquals("user:43", created.subject());
        assertEquals(CustodyWithdrawalService.FundingSource.TENANT_POOL, created.fundingSource());
        var order = new WithdrawalOrderRepository(jdbc).find("ETH", created.orderNo(), tenant).orElseThrow();
        assertEquals(poolAccount(), order.getDebitAccountId());
        assertEquals("0x" + "78".repeat(20), order.getFromAddress());
        assertEquals(0, new BigDecimal("4").compareTo(poolBalance("locked_balance")));
        assertEquals(0, new BigDecimal("1").compareTo(principal("withdraw-account", "available_balance")));
        var query = new CustodyTransactionQueryService(new CustodyDepositRepository(jdbc), new CustodyWithdrawalRepository(jdbc),
                new CustodyAddressRepository(jdbc), new WithdrawalOrderRepository(jdbc), new CustodyGasUsageRepository(jdbc));
        assertEquals(created.id(), query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant), "ETH", "ETH", "", "", "user:43", "", 1, 0).getFirst().id());
        assertTrue(query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant), "ETH", "ETH", "", "", "withdraw-test", "", 1, 0).isEmpty());
    }

    @Test void poolCombinesPrincipalAccountsButExcludesGasAndKeepsExistingLocks() {
        addPoolSource();
        jdbc.update("update ledger_balance set available_balance=2,locked_balance=1,total_balance=3 where tenant_id=? and account_id='withdraw-account'", tenant);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','second-principal',3,0,3)", tenant);
        poolCreate("combined-principal", "4", "user:43");
        assertEquals(0, BigDecimal.ONE.compareTo(principal("withdraw-account", "available_balance")));
        assertEquals(0, BigDecimal.ONE.compareTo(principal("withdraw-account", "locked_balance")));
        assertEquals(0, principal("second-principal", "available_balance").signum());
        assertEquals(0, new BigDecimal("100").compareTo(principal("pool-physical", "available_balance")));
        assertEquals(2, jdbc.queryForObject("select count(*) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_DEBIT'", Integer.class, tenant));
        assertEquals(0, new BigDecimal("4").compareTo(jdbc.queryForObject("select sum(amount) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_DEBIT'", BigDecimal.class, tenant)));
        assertEquals(0, new BigDecimal("4").compareTo(jdbc.queryForObject("select sum(amount) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_CREDIT'", BigDecimal.class, tenant)));
    }

    @Test void insufficientPoolNeverSpendsGasOrLeavesPartialDebitsAndReceipts() {
        addPoolSource();
        assertThrows(IllegalArgumentException.class, () -> poolCreate("pool-too-large", "6", "user:43"));
        assertEquals(0, new BigDecimal("5").compareTo(principal("withdraw-account", "available_balance")));
        assertEquals(0, new BigDecimal("100").compareTo(principal("pool-physical", "available_balance")));
        assertEquals(0, count("custody_ledger_entry"));
        assertEquals(0, count("custody_idempotency_key"));
        assertEquals(0, count("custody_withdrawal"));
        assertEquals(2, count("ledger_balance"));
    }

    @Test void poolFundingRollsBackWithTheAcceptanceGasFailure() {
        addPoolSource();
        rejectGas=true;
        assertThrows(IllegalStateException.class, () -> poolCreate("pool-gas-failure", "4", "user:43"));
        assertEquals(0, new BigDecimal("5").compareTo(principal("withdraw-account", "available_balance")));
        assertEquals(0, count("custody_ledger_entry"));
        assertEquals(0, count("withdrawal_order"));
        assertEquals(0, count("custody_idempotency_key"));
    }

    @Test void poolCannotAdoptAnUnownedLedgerAccount() {
        addPoolSource();
        jdbc.update("insert into ledger_balance(chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values ('ETH','ETH',?,100,0,100)", poolAccount());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> poolCreate("unowned-pool", "4", "user:43"));
        assertNull(jdbc.queryForObject("select tenant_id from ledger_balance where account_id=?", UUID.class, poolAccount()));
        assertEquals(0, count("custody_idempotency_key"));
    }

    @Test void parallelPoolWithdrawalsCannotReserveTheSameTenantPrincipalTwice() throws Exception {
        addPoolSource();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i=0; i<16; i++) {
                int id=i;
                tasks.add(() -> {
                    try { poolCreate("parallel-pool-"+id, "1", "user:"+(43+id)); return true; }
                    catch (IllegalArgumentException insufficient) {
                        assertTrue(insufficient.getMessage().contains("insufficient")); return false;
                    }
                });
            }
            int accepted=0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertEquals(5, accepted);
        } finally { executor.shutdownNow(); }
        assertEquals(5, count("custody_withdrawal"));
        assertEquals(5, count("custody_idempotency_key"));
        assertEquals(0, new BigDecimal("5").compareTo(poolBalance("locked_balance")));
        assertEquals(0, principal("withdraw-account", "available_balance").signum());
        assertEquals(0, new BigDecimal("100").compareTo(principal("pool-physical", "available_balance")));
    }

    @Test void failedPoolPrincipalReleasesOnceAndCanFundTheNextBeneficiary() {
        addPoolSource();
        var failed=poolCreate("pool-failed-first", "4", "user:43");
        String hash="0x"+"ab".repeat(32);
        jdbc.update("update withdrawal_order set status='SENT',tx_hash=? where order_no=?", hash, failed.orderNo());
        var settlements=context.getBean(WithdrawalSettlementService.class);
        assertTrue(settlements.fail(tenant,"ETH",failed.orderNo(),hash,"synthetic canonical failure"));
        assertFalse(settlements.fail(tenant,"ETH",failed.orderNo(),hash,"repeat"));
        assertEquals(0, new BigDecimal("4").compareTo(poolBalance("available_balance")));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select funds_released from withdrawal_order where order_no=?",Boolean.class,failed.orderNo()));
        var next=poolCreate("pool-after-refund", "4", "user:44");
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_CREDIT'",Integer.class,tenant));
        jdbc.update("update withdrawal_order set status='SENT',tx_hash=? where order_no=?",hash,next.orderNo());
        assertTrue(settlements.confirm(tenant,"ETH",next.orderNo(),hash));
        assertFalse(settlements.confirm(tenant,"ETH",next.orderNo(),hash));
        assertEquals(0,poolBalance("total_balance").signum());
        assertEquals(0,BigDecimal.ONE.compareTo(principal("withdraw-account","total_balance")));
    }

    @Test void poolReplayKeepsBeneficiaryAndSurvivesNewAssetDeficits() {
        addPoolSource();
        var original=poolCreate("pool-stable-receipt", "1", "user:43");
        long deposit=jdbc.queryForObject("insert into deposit_record(tenant_id,chain,asset_symbol,tx_hash,to_address,amount,block_height,account_id) values (?,'ETH','ETH','pool-reorg',?,1,1,'withdraw-account') returning id",Long.class,tenant,address);
        new CustodyReorgDeficitRepository(jdbc).open(tenant,custodyId,deposit,"ETH","ETH","withdraw-account",BigDecimal.ONE,BigDecimal.ONE);
        assertEquals(original.id(),poolCreate("pool-stable-receipt","1","user:43").id());
        assertThrows(IllegalStateException.class,()->poolCreate("pool-stable-receipt","1","user:44"));
        assertThrows(IllegalStateException.class,()->poolCreate("pool-new-deficit","1","user:44"));
        assertEquals(1,count("custody_withdrawal"));
    }

    private UUID addPoolSource() {
        UUID id=UUID.randomUUID();
        String physical="0x"+"78".repeat(20);
        long chainAddress=jdbc.queryForObject("insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,address,owner_address,derivation_path,wallet_role,enabled) values (?,'ETH','ETH','pool-physical',3,1,0,?,?,'m/44/60/3/1/0','GAS_FUNDING',true) returning id",Long.class,tenant,physical,physical);
        jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) values (?,?,?,'ETH','sepolia',?,'system:gas','API','ACTIVE',3,0)",id,tenant,chainAddress,physical);
        jdbc.update("insert into custody_gas_account(id,tenant_id,custody_address_id,chain,network,native_symbol,low_balance_threshold,status) values (?,?,?,'ETH','sepolia','ETH',0,'ACTIVE')",UUID.randomUUID(),tenant,id);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','pool-physical',100,0,100)",tenant);
        return id;
    }
    private CustodyWithdrawalService.WithdrawalView poolCreate(String reference,String amount,String subject) {
        var command=new CustodyWithdrawalService.CreateWithdrawalCommand(null,"ETH","ETH",command().toAddress(),amount,reference,true,subject,CustodyWithdrawalService.FundingSource.TENANT_POOL);
        return service.create(CustodyTestSupport.tenantAdmin(tenant),command,"API",reference,"127.0.0.1");
    }
    private String poolAccount() { return CustodyPoolFundingService.accountId(tenant,"ETH","ETH"); }
    private BigDecimal poolBalance(String column) { return principal(poolAccount(),column); }
    private BigDecimal principal(String account,String column) {
        return jdbc.queryForObject("select "+column+" from ledger_balance where tenant_id=? and account_id=?",BigDecimal.class,tenant,account);
    }

    private CustodyWithdrawalService.WithdrawalView create(String sourceIp) {
        return service.create(CustodyTestSupport.tenantAdmin(tenant), command(), "API", "withdrawal-test-request", sourceIp);
    }
    private CustodyWithdrawalService.CreateWithdrawalCommand command() {
        return new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId, "ETH", "ETH", "0x" + "34".repeat(20), "1", "external-test", true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
    }
    private BigDecimal balance(String column) { return jdbc.queryForObject("select " + column + " from ledger_balance where tenant_id=?", BigDecimal.class, tenant); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }

    private static final class CountingJdbc extends JdbcTemplate {
        private final Map<String, Integer> selects = new java.util.concurrent.ConcurrentHashMap<>();
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
