package com.surprising.wallet.custody;

import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.repository.AccountSequenceRepository;
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

/** Real PG18: an unadmitted owner fences later businesses; committed admission permits the next sequence. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class AccountSequenceReservationIntegrationTest {
    private static final String ADDRESS="sequence-test-address";
    private JdbcTemplate jdbc;private AccountSequenceRepository sequences;private TransactionTemplate transaction;
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);
        jdbc=new JdbcTemplate(ds);sequences=new AccountSequenceRepository(jdbc);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @Test void concurrentRetriesRetainOneOriginalAllocationAndBlockOtherBusinesses() throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var retries=IntStream.range(0,32).mapToObj(i->executor.submit(()->reserve(10,"original"))).toList();
            for(var retry:retries)assertEquals(10L,retry.get(20,TimeUnit.SECONDS));
        }
        assertEquals(11L,next());assertThrows(MpcPendingException.class,()->reserve(10,"other"));
        transaction.executeWithoutResult(s->assertFalse(sequences.admit("APTOS",ADDRESS,10,"other")));
        assertThrows(MpcPendingException.class,()->reserve(10,"other"));
        admit(10,"original");assertEquals(11L,reserve(10,"other"));
    }
    @Test void concurrentCommittedAdmissionsAreUniqueAndContiguous() throws Exception {
        var allocated=new HashSet<Long>();
        try(var executor=Executors.newFixedThreadPool(8)) {
            var futures=IntStream.range(0,32).mapToObj(i->executor.submit(()->transaction.execute(s->{
                long value=reserve(50,"owner-"+i);assertTrue(sequences.admit("APTOS",ADDRESS,value,"owner-"+i));return value;
            }))).toList();
            for(var future:futures)assertTrue(allocated.add(future.get(20,TimeUnit.SECONDS)));
        }
        for(long i=50;i<82;i++)assertTrue(allocated.contains(i));assertEquals(82L,next());
    }
    @Test void chainObservationDoesNotRewindCommittedAllocationsOrChangePendingWire() {
        assertEquals(0L,reserve(0,"first"));admit(0,"first");
        assertEquals(10L,reserve(10,"second"));
        sequences.synchronize("APTOS",ADDRESS,30);
        assertThrows(MpcPendingException.class,()->reserve(30,"second"));
        admit(10,"second");assertEquals(30L,reserve(0,"third"));admit(30,"third");
        sequences.synchronize("APTOS",ADDRESS,2);assertEquals(31L,reserve(2,"fourth"));
    }
    @Test void noGrantCancellationReturnsOnlyExclusiveUnusedSuffix() {
        assertEquals(7L,reserve(7,"cancel"));
        assertThrows(IllegalStateException.class,()->sequences.releaseCancelled("APTOS","cancel"));
        transaction.executeWithoutResult(s->assertTrue(sequences.releaseCancelled("APTOS","foreign").isEmpty()));
        assertThrows(MpcPendingException.class,()->reserve(7,"next"));
        transaction.executeWithoutResult(s->assertEquals(7L,sequences.releaseCancelled("APTOS","cancel").getFirst().sequence()));
        assertEquals(7L,reserve(7,"next"));admit(7,"next");
        transaction.executeWithoutResult(s->assertTrue(sequences.releaseCancelled("APTOS","next").isEmpty()));
        assertEquals(8L,reserve(7,"later"));
    }
    @Test void failedProofTransactionRollsBackAdmissionAndNewAllocation() {
        assertEquals(7L,reserve(7,"original"));
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(s->{
            assertTrue(sequences.admit("APTOS",ADDRESS,7,"original"));
            assertEquals(8L,reserve(0,"later"));throw new IllegalStateException("proof persistence failed");
        }));
        assertEquals(7L,reserve(7,"original"));assertThrows(MpcPendingException.class,()->reserve(7,"later"));assertEquals(8L,next());
    }
    @Test void chainsAddressesAndTimestampDomainsAreIndependent() {
        assertEquals(3L,reserve(3,"aptos"));assertEquals(3L,sequences.reserve("TON",ADDRESS,3,"ton"));
        assertEquals(3L,sequences.reserve("APTOS","another-address",3,"another"));
        assertEquals(100L,sequences.reserveTimestamp(ADDRESS,100));assertEquals(101L,sequences.reserveTimestamp(ADDRESS,99));
        assertThrows(IllegalArgumentException.class,()->sequences.reserve("HYPERCORE",ADDRESS,1,"wrong-domain"));
    }
    @Test void invalidInputsAndExhaustionCannotWrapAround() {
        assertThrows(IllegalArgumentException.class,()->reserve(-1,"invalid"));assertThrows(IllegalArgumentException.class,()->reserve(Long.MAX_VALUE,"invalid"));
        assertEquals(Long.MAX_VALUE-1,reserve(Long.MAX_VALUE-1,"last"));admit(Long.MAX_VALUE-1,"last");
        assertThrows(IllegalStateException.class,()->reserve(0,"exhausted"));assertEquals(Long.MAX_VALUE,next());
    }
    private long reserve(long observed,String owner){return sequences.reserve("APTOS",ADDRESS,observed,owner);}
    private void admit(long value,String owner){transaction.executeWithoutResult(s->assertTrue(sequences.admit("APTOS",ADDRESS,value,owner)));}
    private long next(){return jdbc.queryForObject("select next_sequence from account_sequence where chain='APTOS' and address=?",Long.class,ADDRESS);}
}
