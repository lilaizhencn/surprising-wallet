package com.surprising.wallet.custody;

import com.surprising.wallet.repository.AccountSequenceRepository;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** The counter remains atomic without a facade or an enclosing Spring transaction. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class AccountSequenceReservationIntegrationTest {
    private static final String ADDRESS = "sequence-test-address";
    private JdbcTemplate jdbc;
    private AccountSequenceRepository sequences;

    @BeforeEach void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        sequences = new AccountSequenceRepository(jdbc);
    }

    @Test void concurrentStandaloneReservationsAreUniqueAndContiguous() throws Exception {
        var allocated = new HashSet<Long>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 64).mapToObj(i -> executor.submit(() -> reserve(10))).toList();
            for (var future : futures) assertTrue(allocated.add(future.get(20, TimeUnit.SECONDS)));
        }
        assertEquals(64, allocated.size());
        for (long sequence = 10; sequence < 74; sequence++) assertTrue(allocated.contains(sequence));
        assertEquals(74L, next());
    }

    @Test void newerChainStateAdvancesAndStaleStateCannotRewindReservations() {
        assertEquals(0L, reserve(0));
        assertEquals(1L, reserve(0));
        assertEquals(10L, reserve(10));
        assertEquals(11L, reserve(0));
        sequences.synchronize("APTOS", ADDRESS, 30);
        assertEquals(30L, reserve(0));
        sequences.synchronize("APTOS", ADDRESS, 2);
        assertEquals(31L, reserve(2));
        assertEquals(32L, next());
    }

    @Test void concurrentSynchronizationDoesNotDuplicateAnAllocatedSequence() throws Exception {
        var allocated = new HashSet<Long>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 32).mapToObj(i -> executor.submit(() -> {
                sequences.synchronize("APTOS", ADDRESS, 50);
                return reserve(50);
            })).toList();
            for (var future : futures) assertTrue(allocated.add(future.get(20, TimeUnit.SECONDS)));
        }
        assertEquals(82L, next());
        for (long sequence = 50; sequence < 82; sequence++) assertTrue(allocated.contains(sequence));
    }

    @Test void failedOuterTransactionRollsBackExistingAndNewReservations() {
        assertEquals(7L, reserve(7));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(8L, reserve(0));
            assertEquals(2L, sequences.reserve("TON", ADDRESS, 2));
            throw new IllegalStateException("signed attempt persistence failed");
        }));
        assertEquals(8L, reserve(0));
        assertEquals(0, jdbc.queryForObject("select count(*) from account_sequence where chain = 'TON'", Integer.class));
        assertEquals(2L, sequences.reserve("TON", ADDRESS, 2));
    }

    @Test void chainsAndAddressesHaveIndependentCounters() {
        assertEquals(3L, reserve(3));
        assertEquals(3L, sequences.reserve("TON", ADDRESS, 3));
        assertEquals(3L, sequences.reserve("APTOS", "another-address", 3));
        assertEquals(4L, reserve(0));
    }

    @Test void invalidInputsAndAnExhaustedCounterCannotWrapAround() {
        assertThrows(IllegalArgumentException.class, () -> reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> reserve(Long.MAX_VALUE));
        assertEquals(0, jdbc.queryForObject("select count(*) from account_sequence", Integer.class));
        assertEquals(Long.MAX_VALUE - 1, reserve(Long.MAX_VALUE - 1));
        assertThrows(DataIntegrityViolationException.class, () -> reserve(0));
        assertEquals(Long.MAX_VALUE, next());
    }

    private long reserve(long observedSequence) { return sequences.reserve("APTOS", ADDRESS, observedSequence); }

    private long next() {
        return jdbc.queryForObject("select next_sequence from account_sequence where chain = 'APTOS' and address = ?",
                Long.class, ADDRESS);
    }
}
