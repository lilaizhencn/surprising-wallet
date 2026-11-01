package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.TonTransactionRecord;
import com.surprising.wallet.repository.TonTransactionRepository;
import java.math.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG18 protects selected BOC, terminal execution facts and transactional evidence. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class TonTransactionPersistenceIntegrationTest {
    private JdbcTemplate jdbc;private TonTransactionRepository transactions;private TransactionTemplate transaction;
    @BeforeEach void setup()throws Exception{var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);transactions=new TonTransactionRepository(jdbc);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));}
    private TonTransactionRecord row(String status,String raw,String proof,long fee){return TonTransactionRecord.builder().chain("TON").txHash("ab".repeat(32))
            .fromAddress("0:"+"12".repeat(32)).toAddress("0:"+"34".repeat(32)).assetSymbol("TON").amount(BigDecimal.ONE).feeNano(fee)
            .logicalTime(status.equals("PREPARED")?null:BigInteger.valueOf(123456789)).confirmations(status.equals("CONFIRMED")||status.equals("FAILED")?1:0)
            .status(status).rawPayload(raw).executionReceipt(proof).build();}
    @Test void firstSelectedBocAndFinalReceiptSurviveLateBroadcastObservations(){
        String proof="{\"owner\":{\"transaction_id\":{\"hash\":\"actual-owner-transaction\",\"lt\":\"123456789\"}}}";
        transactions.upsert(row("PREPARED","selected-boc",null,0));transactions.upsert(row("CONFIRMED","selected-boc",proof,7654321));
        transactions.upsert(row("PREPARED","replacement-boc","{\"unrelated\":true}",1));assertEquals(0,transactions.markSubmitted("ab".repeat(32)));
        var stored=transactions.findSigned("ab".repeat(32)).orElseThrow();assertEquals("CONFIRMED",stored.getStatus());assertEquals("selected-boc",stored.getRawPayload());
        assertEquals(7654321L,stored.getFeeNano());assertEquals(1,stored.getConfirmations());assertEquals(BigInteger.valueOf(123456789),stored.getLogicalTime());
        assertEquals(new ObjectMapper().readTree(proof),new ObjectMapper().readTree(stored.getExecutionReceipt()));
    }
    @Test void confirmedFailureRetainsItsExpenseAndCannotBeResurrected(){
        transactions.upsert(row("PREPARED","selected-boc",null,0));transactions.upsert(row("FAILED","selected-boc","{\"failure\":\"proven-child-execution\"}",12345));
        transactions.upsert(row("CONFIRMED","replacement",null,999));assertEquals(0,transactions.updateDepositConfirmations("TON","ab".repeat(32),20,1));
        assertEquals("FAILED",transactions.findSigned("ab".repeat(32)).orElseThrow().getStatus());
        assertEquals(0,BigDecimal.valueOf(12345).compareTo(transactions.findConfirmedFeeAtomic("TON","ab".repeat(32)).orElseThrow()));
    }
    @Test void evidenceAndRawShareRollbackAndInvalidEvidenceCannotBeWritten(){
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(s->{transactions.upsert(row("CONFIRMED","selected-boc","{\"actual\":true}",1));throw new IllegalStateException("reject outer settlement");}));
        assertTrue(transactions.findSigned("ab".repeat(32)).isEmpty());
        assertThrows(DataAccessException.class,()->transactions.upsert(row("CONFIRMED","selected-boc","[]",1)));
        assertThrows(DataAccessException.class,()->transactions.upsert(row("CONFIRMED","selected-boc","{}",-1)));
        assertTrue(transactions.findSigned("ab".repeat(32)).isEmpty());
    }
}
