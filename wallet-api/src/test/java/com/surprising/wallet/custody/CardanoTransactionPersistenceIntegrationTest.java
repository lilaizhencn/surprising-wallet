package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.CardanoOwnedOutput;
import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.CardanoOwnedOutputRepository;
import com.surprising.wallet.repository.CardanoTransactionRepository;
import com.surprising.wallet.repository.ChainScanBlockRepository;
import com.surprising.wallet.repository.ChainScanHeightRepository;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG18 storage/rollback/fencing only; signatures are not part of this test. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CardanoTransactionPersistenceIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String GENESIS = "11".repeat(32), HASH = "aa".repeat(32), SCANNER = "cardano-native-scanner";
    private static final ChainScanPoint START = new ChainScanPoint(100, 200, "22".repeat(32));
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private TransactionTemplate transaction;
    private ChainAddressRecord source;
    private CardanoTransactionRepository transactions;
    private CardanoOwnedOutputRepository outputs;
    private ChainScanHeightRepository heights;
    private ChainScanBlockRepository blocks;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource(); CustodyIntegrationDatabase.reset(ds); jdbc = new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='ADA'");
        jdbc.update("update chain_profile set enabled=true where chain='ADA' and network='preprod'");
        fixture = new MpcSigningTestSupport(jdbc); source = fixture.address(jdbc, TENANT, ChainType.ADA, 42, 1, "DEPOSIT");
        transaction = new TransactionTemplate(fixture.manager);
        transactions = new CardanoTransactionRepository(jdbc); outputs = new CardanoOwnedOutputRepository(jdbc);
        heights = new ChainScanHeightRepository(jdbc); blocks = new ChainScanBlockRepository(jdbc);
    }

    @Test void bodyIdentityIsDurableBeforeWitnessAndForeignTenantCannotAccessOrBind() {
        var request = pending("first", HASH);
        var row = transactions.find(TENANT, request.id()).orElseThrow();
        assertEquals(HASH, row.txHash()); assertNull(row.rawPayload()); assertEquals("SIGNING", row.status());
        assertEquals(START, row.observed()); assertEquals(START, row.searched());
        assertTrue(transactions.find(UUID.randomUUID(), request.id()).isEmpty());
        assertEquals(0, transactions.bindSigned(UUID.randomUUID(), request.id(), HASH, "84"));
        assertEquals(0, transactions.bindSigned(TENANT, request.id(), "bb".repeat(32), "84"));
        assertEquals(1, transactions.bindSigned(TENANT, request.id(), HASH, "84"));
        assertEquals(0, transactions.bindSigned(TENANT, request.id(), HASH, "85"));
        assertEquals("84", transactions.find(TENANT, request.id()).orElseThrow().rawPayload());
    }

    @Test void unknownPendingPaymentSerializesSourceAcrossAllAssetsAndRequests() {
        pending("native", HASH);
        assertTrue(transactions.hasPendingSender(source.getAddress()));
        assertThrows(DataAccessException.class, () -> pending("token", "bb".repeat(32)));
        assertTrue(new WalletMpcSignRequestRepository(jdbc).findBusiness(TENANT, ChainType.ADA,
                MpcSignCommand.BusinessType.DEV_FAUCET, "token", "transaction").isEmpty());
    }

    @Test void requestAndAdmissionRollbackTogether() {
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            var request = request("rollback");
            assertEquals(1, transactions.insertPending(request.id(), TENANT, source.getAddress(), HASH, START));
            throw new IllegalStateException("reject owning workflow");
        }));
        assertFalse(transactions.hasPendingSender(source.getAddress()));
        assertTrue(new WalletMpcSignRequestRepository(jdbc).findBusiness(TENANT, ChainType.ADA,
                MpcSignCommand.BusinessType.DEV_FAUCET, "rollback", "transaction").isEmpty());
    }

    @Test void boundedSearchUsesExactIntersectionAndCannotSwitchForkAtSameHeight() {
        var request = pending("search", HASH); transactions.bindSigned(TENANT, request.id(), HASH, "84");
        var next = new ChainScanPoint(102, 207, "33".repeat(32));
        assertEquals(1, transactions.advanceSearch(TENANT, request.id(), START, next));
        assertEquals(0, transactions.advanceSearch(TENANT, request.id(), START, next));
        assertThrows(IllegalArgumentException.class, () -> transactions.advanceSearch(TENANT, request.id(), next,
                new ChainScanPoint(next.height(), next.slot(), "44".repeat(32))));
        assertEquals(next, transactions.find(TENANT, request.id()).orElseThrow().searched());
    }

    @Test void firstProofIsImmutableAndKnownZeroExpenseDoesNotDisappear() {
        var request = pending("proof", HASH); transactions.bindSigned(TENANT, request.id(), HASH, "84");
        assertEquals(1, transactions.markSubmitted(TENANT, request.id()));
        String proof = "{\"kind\":\"EXPIRED_UNSPENT\",\"test\":true}";
        assertEquals(1, transactions.recordProof(TENANT, request.id(), proof, BigInteger.ZERO, BigInteger.ZERO, 101, "CONFIRMING"));
        assertEquals(0, transactions.recordProof(TENANT, request.id(), "{}", BigInteger.ONE, BigInteger.ONE, 101, "FAILED"));
        assertEquals(1, transactions.recordProof(TENANT, request.id(), proof, BigInteger.ZERO, BigInteger.ZERO, 101, "FAILED"));
        assertEquals(0, transactions.markSubmitted(TENANT, request.id()));
        assertEquals(0, transactions.bindSigned(TENANT, request.id(), HASH, "85"));
        assertEquals(0, transactions.findConfirmedExpense(HASH).orElseThrow().compareTo(BigDecimal.ZERO));
        assertFalse(transactions.hasPendingSender(source.getAddress()));
    }

    @Test void ownedOutputCompositeForeignKeyAndReorgRollbackProtectTenantAndCursor() {
        UUID foreign = UUID.randomUUID(); new CustodyTenantTableRepository(jdbc).insert(foreign, "cardano-foreign", "Foreign");
        var forged = output(foreign, 101);
        assertThrows(DataAccessException.class, () -> outputs.insert(forged));
        assertEquals(1, outputs.insert(output(TENANT, 101)));
        assertEquals(0, outputs.insert(output(TENANT, 101)));
        assertEquals(TENANT, outputs.findByTransactions(List.of(HASH)).getFirst().tenantId());
        transaction.executeWithoutResult(status -> {
            assertEquals(1, heights.initializeNative("ADA", SCANNER, GENESIS, START.height()));
            blocks.insertNative("ADA", SCANNER, START, "00".repeat(32));
        });
        var next = new ChainScanPoint(101, 203, "33".repeat(32));
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            heights.nativeState("ADA", SCANNER, true).orElseThrow();
            blocks.insertNative("ADA", SCANNER, next, START.hash());
            assertEquals(1, heights.advanceNative("ADA", SCANNER, GENESIS, 101, 100, 101));
            outputs.removeAbove(100);
            throw new IllegalStateException("reject projection or reorg settlement");
        }));
        assertEquals(100, heights.nativeState("ADA", SCANNER, false).orElseThrow().safeHeight());
        assertEquals(START, blocks.nativePoint("ADA", SCANNER, 100).orElseThrow().point());
        assertTrue(blocks.nativePoint("ADA", SCANNER, 101).isEmpty());
        assertEquals(1, outputs.findByTransactions(List.of(HASH)).size());
        assertEquals(0, heights.advanceNative("ADA", SCANNER, "ff".repeat(32), 101, 100, 101));
    }

    private MpcSignRequest pending(String reference, String hash) {
        return transaction.execute(status -> {
            var request = request(reference);
            assertEquals(1, transactions.insertPending(request.id(), TENANT, source.getAddress(), hash, START));
            return request;
        });
    }
    private MpcSignRequest request(String reference) {
        var key = fixture.signing.requireKey(TENANT, source.getMpcKeyId());
        return fixture.signing.reserveSignature(new MpcSignCommand(TENANT, key.id(), key.activeEpoch(), ChainType.ADA,
                MpcSignCommand.BusinessType.DEV_FAUCET, reference, "transaction", MpcSignCommand.Purpose.TRANSACTION,
                "TEST_STORAGE_ONLY", "7b7d", "00"), fixture.nodes.online());
    }
    private CardanoOwnedOutput output(UUID tenant, long height) {
        return new CardanoOwnedOutput(HASH, 0, tenant, source.getId(), source.getAddress(), height, "44".repeat(32), "80");
    }
}
