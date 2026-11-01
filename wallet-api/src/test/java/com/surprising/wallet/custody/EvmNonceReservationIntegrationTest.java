package com.surprising.wallet.custody;

import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.repository.EvmNonceRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG18: full-width EVM counters admit once, and never rewind a business that received a proof. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class EvmNonceReservationIntegrationTest {
    private static final String ADDRESS="0x0000000000000000000000000000000000000001";
    private JdbcTemplate jdbc;private EvmNonceRepository nonces;private TransactionTemplate transaction;
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);
        jdbc=new JdbcTemplate(ds);nonces=new EvmNonceRepository(jdbc);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @Test void concurrentRetriesUseTheOriginalNonceAndOtherBusinessWaits() throws Exception {
        var start=BigInteger.ONE.shiftLeft(80);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var retries=IntStream.range(0,32).mapToObj(i->executor.submit(()->reserve(start,"original"))).toList();
            for(var retry:retries)assertEquals(start,retry.get(20,TimeUnit.SECONDS));
        }
        assertEquals(start.add(BigInteger.ONE),stored("reserved_nonce"));
        assertThrows(MpcPendingException.class,()->reserve(start,"other"));
        transaction.executeWithoutResult(s->assertFalse(nonces.admit("ETH",ADDRESS,start,"other")));
        admit(start,"original");assertEquals(start.add(BigInteger.ONE),reserve(start,"other"));
    }
    @Test void concurrentCommittedAdmissionsRemainContiguousBeyondLongRange() throws Exception {
        var start=BigInteger.ONE.shiftLeft(80);var allocated=new HashSet<BigInteger>();
        try(var executor=Executors.newFixedThreadPool(8)) {
            var futures=IntStream.range(0,32).mapToObj(i->executor.submit(()->transaction.execute(s->{
                var value=reserve(start,"owner-"+i);assertTrue(nonces.admit("ETH",ADDRESS,value,"owner-"+i));return value;
            }))).toList();
            for(var future:futures)assertTrue(allocated.add(future.get(20,TimeUnit.SECONDS)));
        }
        for(int i=0;i<32;i++)assertTrue(allocated.contains(start.add(BigInteger.valueOf(i))));assertEquals(start.add(BigInteger.valueOf(32)),stored("reserved_nonce"));
    }
    @Test void chainAdvanceCannotChangeAnUnadmittedWireOrRewindLaterAdmission() {
        assertEquals(BigInteger.ZERO,reserve(BigInteger.ZERO,"first"));
        assertThrows(MpcPendingException.class,()->reserve(BigInteger.TEN,"first"));
        admit(BigInteger.ZERO,"first");assertEquals(BigInteger.TEN,reserve(BigInteger.TEN,"second"));admit(BigInteger.TEN,"second");
        assertEquals(BigInteger.valueOf(11),reserve(BigInteger.ZERO,"third"));assertEquals(BigInteger.TEN,stored("chain_nonce"));
    }
    @Test void cancellationOnlyReturnsAnExclusiveUnadmittedNonce() {
        assertEquals(BigInteger.TEN,reserve(BigInteger.TEN,"cancel"));
        assertThrows(IllegalStateException.class,()->nonces.releaseCancelled("ETH","cancel"));
        transaction.executeWithoutResult(s->assertTrue(nonces.releaseCancelled("ETH","foreign").isEmpty()));
        assertThrows(MpcPendingException.class,()->reserve(BigInteger.TEN,"next"));
        transaction.executeWithoutResult(s->assertEquals(BigInteger.TEN,nonces.releaseCancelled("ETH","cancel").getFirst().nonce()));
        assertEquals(BigInteger.TEN,reserve(BigInteger.TEN,"next"));admit(BigInteger.TEN,"next");
        transaction.executeWithoutResult(s->assertTrue(nonces.releaseCancelled("ETH","next").isEmpty()));
        assertEquals(BigInteger.valueOf(11),reserve(BigInteger.TEN,"later"));
    }
    @Test void failedProofTransactionRestoresPendingOwner() {
        assertEquals(BigInteger.ZERO,reserve(BigInteger.ZERO,"original"));
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(s->{
            assertTrue(nonces.admit("ETH",ADDRESS,BigInteger.ZERO,"original"));reserve(BigInteger.ZERO,"later");throw new IllegalStateException("proof persistence failed");
        }));
        assertEquals(BigInteger.ZERO,reserve(BigInteger.ZERO,"original"));assertThrows(MpcPendingException.class,()->reserve(BigInteger.ZERO,"later"));
        assertEquals(BigInteger.ONE,stored("reserved_nonce"));
    }
    @Test void invalidInputsAndUint256ExhaustionCannotCreateInvalidNonce() {
        assertThrows(IllegalArgumentException.class,()->reserve(null,"invalid"));assertThrows(IllegalArgumentException.class,()->reserve(BigInteger.valueOf(-1),"invalid"));
        assertThrows(IllegalArgumentException.class,()->reserve(BigInteger.ONE.shiftLeft(256),"invalid"));
        var maximum=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);assertEquals(maximum,reserve(maximum,"last"));admit(maximum,"last");
        assertThrows(IllegalStateException.class,()->reserve(BigInteger.ZERO,"exhausted"));assertEquals(maximum.add(BigInteger.ONE),stored("reserved_nonce"));
    }
    @Test void chainsAndAddressesHaveIndependentPendingOwners() {
        assertEquals(BigInteger.ZERO,reserve(BigInteger.ZERO,"eth"));
        assertEquals(BigInteger.ZERO,nonces.reserve("BASE",ADDRESS,BigInteger.ZERO,"base"));
        assertEquals(BigInteger.ZERO,nonces.reserve("ETH","another-address",BigInteger.ZERO,"another"));
    }
    private BigInteger reserve(BigInteger observed,String owner){return nonces.reserve("ETH",ADDRESS,observed,owner);}
    private void admit(BigInteger value,String owner){transaction.executeWithoutResult(s->assertTrue(nonces.admit("ETH",ADDRESS,value,owner)));}
    private BigInteger stored(String column){return jdbc.queryForObject("select "+column+" from evm_nonce where chain='ETH' and address=?",BigDecimal.class,ADDRESS).toBigIntegerExact();}
}
