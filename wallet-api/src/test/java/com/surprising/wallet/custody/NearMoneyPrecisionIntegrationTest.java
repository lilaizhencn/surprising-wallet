package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG18 regressions: one yocto cannot disappear between chain, mandatory projection and the owned ledger. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class NearMoneyPrecisionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BigDecimal YOCTO=new BigDecimal("0.000000000000000000000001");
    private JdbcTemplate jdbc;
    private ChainAddressRecord source;
    private AnnotationConfigApplicationContext context;
    private DepositService deposits;
    private LedgerBalanceRepository balances;
    private WithdrawalOrderRepository orders;
    private TransactionTemplate transaction;
    @BeforeEach void setup()throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='NEAR'");
        jdbc.update("update chain_profile set enabled=true where chain='NEAR' and network='testnet'");
        var signing=new MpcSigningTestSupport(jdbc);source=signing.address(jdbc,TENANT,ChainType.NEAR,42,1,"DEPOSIT");
        DepositTestSupport.attachCustodyAddress(jdbc,TENANT,"NEAR","NEAR",source.getAddress());
        context=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper());
        deposits=context.getBean(DepositService.class);balances=new LedgerBalanceRepository(jdbc);orders=new WithdrawalOrderRepository(jdbc);transaction=new TransactionTemplate(signing.manager);
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private DepositEvent event(BigDecimal amount){return new DepositEvent(ChainType.NEAR,"NEAR","11111111111111111111111111111111","funder.near",source.getAddress(),amount,1,"precision-block",1,null,"{\"test\":\"one-yocto-money-path\"}");}
    private void credit(BigDecimal amount){assertTrue(deposits.recordAndCredit(event(amount),0,1,source.getAccountId()));}
    private void amount(BigDecimal expected,BigDecimal actual){assertEquals(0,expected.compareTo(actual),"smallest-unit amount must survive without rounding");}
    @Test void oneYoctoDepositCreditsEveryProjectionOnceWithoutRounding() {
        credit(YOCTO);assertFalse(deposits.recordAndCredit(event(YOCTO),0,1,source.getAccountId()));
        amount(YOCTO,balances.find(TENANT,"NEAR","NEAR",source.getAccountId()).orElseThrow().getAvailableBalance());
        for(String table:List.of("deposit_record","custody_deposit","custody_ledger_entry"))amount(YOCTO,jdbc.queryForObject("select amount from "+table+" where tenant_id=? and chain='NEAR'",BigDecimal.class,TENANT));
    }
    @Test void oneYoctoPrincipalAndFeeSettleAndRefundExactlyOnce() {
        credit(YOCTO.multiply(BigDecimal.TEN));var settlements=new WithdrawalSettlementService(orders,balances,
                com.surprising.wallet.testutil.CustodyTestSupport.hotReservationLifecycle(jdbc));
        sent("precision-paid");assertTrue(Boolean.TRUE.equals(transaction.execute(status->settlements.confirm(TENANT,"NEAR","precision-paid","precision-paid-hash"))));
        assertFalse(Boolean.TRUE.equals(transaction.execute(status->settlements.confirm(TENANT,"NEAR","precision-paid","precision-paid-hash"))));
        sent("precision-failed");assertTrue(Boolean.TRUE.equals(transaction.execute(status->settlements.fail(TENANT,"NEAR","precision-failed","precision-failed-hash","verified failure"))));
        assertFalse(Boolean.TRUE.equals(transaction.execute(status->settlements.fail(TENANT,"NEAR","precision-failed","precision-failed-hash","replay"))));
        var row=balances.find(TENANT,"NEAR","NEAR",source.getAccountId()).orElseThrow();amount(YOCTO.multiply(BigDecimal.valueOf(8)),row.getTotalBalance());amount(row.getTotalBalance(),row.getAvailableBalance());assertEquals(0,row.getLockedBalance().signum());
        var paid=orders.find("NEAR","precision-paid",TENANT).orElseThrow();amount(YOCTO,paid.getAmount());amount(YOCTO,paid.getFee());
    }
    private void sent(String reference){transaction.executeWithoutResult(status->{
        orders.createForTenant(TENANT,reference,42,"NEAR","NEAR",source.getAddress(),source.getAccountId(),"receiver.near",YOCTO,YOCTO);
        assertTrue(balances.freeze("NEAR","NEAR",source.getAccountId(),YOCTO.add(YOCTO),TENANT));
        orders.updateStatus(TENANT,"NEAR",reference,"FROZEN",source.getAddress(),null,null);assertEquals(1,orders.claimSigningDurable(TENANT,"NEAR",reference,source.getAddress()));
        assertEquals(1,orders.markSent(TENANT,"NEAR",reference,source.getAddress(),reference+"-hash"));
    });}
    @Test void actualContractExpenseAndOneYoctoCollectionRemainExact() {
        UUID custody=jdbc.queryForObject("select id from custody_address where tenant_id=? and chain_address_id=?",UUID.class,TENANT,source.getId());
        var expense=new BigDecimal(new BigInteger("1282350138824700000001"),24);
        new CollectionRecordRepository(jdbc).create(TENANT,custody,"precision-collection","NEAR","NEAR",source.getAddress(),"collector.near",YOCTO,expense,null);
        amount(YOCTO,jdbc.queryForObject("select amount from collection_record where tenant_id=? and collection_no='precision-collection'",BigDecimal.class,TENANT));
        amount(expense,jdbc.queryForObject("select fee from collection_record where tenant_id=? and collection_no='precision-collection'",BigDecimal.class,TENANT));
    }
    @Test void finerFractionalPrecisionRetainsTheExistingIntegerRange() {
        var large=new BigDecimal("1e59");balances.increment(TENANT,"NEAR","NEAR","range-fixture",large);
        amount(large,balances.find(TENANT,"NEAR","NEAR","range-fixture").orElseThrow().getTotalBalance());
    }
}
