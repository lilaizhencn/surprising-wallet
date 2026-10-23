package com.surprising.wallet.custody;

import com.surprising.wallet.repository.EvmNonceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real PG18 allocation without an application service or transaction-proxy dependency. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class EvmNonceReservationIntegrationTest {
    private static final String ADDRESS = "0x0000000000000000000000000000000000000001";
    private JdbcTemplate jdbc;
    private EvmNonceRepository nonces;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        nonces = new EvmNonceRepository(jdbc);
    }

    @Test void concurrentStandaloneReservationsAreContiguousBeyondLongRange() throws Exception {
        BigInteger start = BigInteger.ONE.shiftLeft(80);
        var allocated = new HashSet<BigInteger>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 64).mapToObj(i -> executor.submit(() ->
                    nonces.reserve("ETH", ADDRESS, start))).toList();
            for (var future : futures) assertTrue(allocated.add(future.get(20, TimeUnit.SECONDS)));
        }
        assertEquals(64, allocated.size());
        for (int i = 0; i < 64; i++) assertTrue(allocated.contains(start.add(BigInteger.valueOf(i))));
        assertEquals(start.add(BigInteger.valueOf(64)), stored("reserved_nonce"));
    }

    @Test void chainAdvanceWinsAndStaleRpcValuesCannotRewindReservations() {
        assertEquals(BigInteger.ZERO, reserve(0));
        assertEquals(BigInteger.ONE, reserve(0));
        assertEquals(BigInteger.TEN, reserve(10));
        assertEquals(BigInteger.valueOf(11), reserve(0));
        assertEquals(BigInteger.TEN, stored("chain_nonce"));
        assertEquals(BigInteger.valueOf(12), stored("reserved_nonce"));
    }

    @Test void invalidInputsDoNotCreateANonceRow() {
        assertThrows(IllegalArgumentException.class, () -> nonces.reserve("ETH", ADDRESS, null));
        assertThrows(IllegalArgumentException.class, () -> reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> nonces.reserve("ETH", ADDRESS, BigInteger.ONE.shiftLeft(256)));
        assertEquals(0, jdbc.queryForObject("select count(*) from evm_nonce", Integer.class));
    }

    @Test void uint256BoundaryCannotAllocateAnOutOfRangeNonce() {
        BigInteger maximum = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        assertEquals(maximum, nonces.reserve("ETH", ADDRESS, maximum));
        assertThrows(IllegalStateException.class, () -> reserve(0));
        assertEquals(maximum.add(BigInteger.ONE), stored("reserved_nonce"));
    }

    @Test void outerRollbackRestoresTheReservedNonceForRetry() {
        assertEquals(BigInteger.ZERO, reserve(0));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(BigInteger.ONE, reserve(0));
            throw new IllegalStateException("signed attempt failed");
        }));
        assertEquals(BigInteger.ONE, reserve(0));
        assertEquals(BigInteger.TWO, stored("reserved_nonce"));
    }

    @Test void chainsAndAddressesHaveIndependentCounters() {
        assertEquals(BigInteger.ZERO, reserve(0));
        assertEquals(BigInteger.ZERO, nonces.reserve("BASE", ADDRESS, BigInteger.ZERO));
        assertEquals(BigInteger.ZERO, nonces.reserve("ETH", "another-address", BigInteger.ZERO));
        assertEquals(BigInteger.ONE, reserve(0));
    }

    private BigInteger reserve(long nonce) { return nonces.reserve("ETH", ADDRESS, BigInteger.valueOf(nonce)); }

    private BigInteger stored(String column) {
        return jdbc.queryForObject("select " + column + " from evm_nonce where chain='ETH' and address=?",
                BigDecimal.class, ADDRESS).toBigIntegerExact();
    }
}
