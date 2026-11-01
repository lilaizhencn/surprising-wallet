package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.coordinator.Evm7702CollectionCoordinator;
import com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.Evm7702ConfigRepository;
import com.surprising.wallet.repository.EvmCollectionBatchRepository;
import com.surprising.wallet.repository.EvmWithdrawalBatchRepository;
import com.surprising.wallet.testutil.CustodyTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real PG18 claim durability/tenant/immutability checks; no signing transport is substituted or exercised. */
class Evm7702ClaimPersistenceIntegrationTest {
    private static final String AUTHORITY = "0x" + "11".repeat(20);
    private static final String HOT = "0x" + "22".repeat(20);
    private static final String RELAYER = "0x" + "33".repeat(20);
    private static final String HASH = "0x" + "ab".repeat(32);
    private static final String NATIVE = "0x" + "00".repeat(20);
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private UUID tenant;
    private UUID id;

    @BeforeEach void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        jdbc = new JdbcTemplate(source);
        tenant = UUID.randomUUID();
        id = UUID.randomUUID();
        new CustodyTenantTableRepository(jdbc).insert(tenant, "7702-claim-" + tenant, "Original claim acceptance");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void freshCoordinatorRestoresExactOriginalOwnedClaimWhileNewBusinessIsDisabled(boolean collection) {
        Object original = snapshot(collection);
        String payload = json.writeValueAsString(original);
        assertEquals(1, insert(collection, payload));
        jdbc.update("update custody_tenant set status='SUSPENDED' where id=?", tenant);
        jdbc.update("update chain_profile set enabled=false where chain='ETH'");
        jdbc.update("update " + table(collection) + " set status='SIGNING' where id=?", id);
        Object restored;
        if (collection) {
            var coordinator = CustodyTestSupport.evmCollections(jdbc);
            restored = coordinator.claimed(tenant, id).orElseThrow();
            assertTrue(coordinator.claimed(UUID.randomUUID(), id).isEmpty());
            assertEquals(1, coordinator.listSigning("ETH", "sepolia", 10).size());
            assertTrue(coordinator.listSigning("ETH", "another-network", 10).isEmpty());
        } else {
            var coordinator = CustodyTestSupport.evmWithdrawals(jdbc);
            restored = coordinator.claimed(tenant, id).orElseThrow();
            assertTrue(coordinator.claimed(UUID.randomUUID(), id).isEmpty());
            assertEquals(1, coordinator.listSigning("ETH", "sepolia", 10).size());
            assertTrue(coordinator.listSigning("ETH", "another-network", 10).isEmpty());
        }
        assertEquals(json.readTree(payload), json.readTree(json.writeValueAsString(restored)));
        jdbc.update("update " + table(collection) + " set status='CONFIRMED' where id=?", id);
        assertTrue(collection ? new EvmCollectionBatchRepository(jdbc).listSigning("ETH", "sepolia", 10).isEmpty()
                : new EvmWithdrawalBatchRepository(jdbc).listSigning("ETH", "sepolia", 10).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void databaseRejectsRewritingTheSnapshotOrItsFinancialIdentity(boolean collection) {
        String payload = json.writeValueAsString(snapshot(collection));
        insert(collection, payload);
        for (String assignment : List.of("claim_payload=jsonb_set(claim_payload,'{hotWallet}','\"" + AUTHORITY + "\"')",
                "hot_wallet='" + AUTHORITY + "'", "token_decimals=6", "delegate_version=2", "item_count=2")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("update " + table(collection) + " set " + assignment + " where id=?", id));
        }
        String actual = collection ? new EvmCollectionBatchRepository(jdbc).claimPayload(tenant, id).orElseThrow()
                : new EvmWithdrawalBatchRepository(jdbc).claimPayload(tenant, id).orElseThrow();
        assertEquals(json.readTree(payload), json.readTree(actual));
        assertEquals(1, jdbc.update("update " + table(collection) + " set error_code='PENDING_RECONCILIATION' where id=?", id));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingMalformedOrForeignClaimCannotBeInserted(boolean collection) {
        String valid = json.writeValueAsString(snapshot(collection));
        var foreign = json.readTree(valid).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)foreign).put("tenantId", UUID.randomUUID().toString());
        var nullIdentity = (tools.jackson.databind.node.ObjectNode)json.readTree(valid).deepCopy();
        nullIdentity.putNull("id");
        for (String invalid : Arrays.asList(null, "{}", "[]", foreign.toString(), nullIdentity.toString())) {
            assertThrows(DataIntegrityViolationException.class, () -> insert(collection, invalid));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from " + table(collection), Integer.class));
    }

    private int insert(boolean collection, String payload) {
        return collection ? new EvmCollectionBatchRepository(jdbc).insert(id, tenant, "ETH", "sepolia", "ETH",
                NATIVE, 18, HOT, RELAYER, 1, HASH, 1, payload)
                : new EvmWithdrawalBatchRepository(jdbc).insert(id, tenant, "ETH", "sepolia", "ETH",
                NATIVE, 18, HOT, RELAYER, 1, HASH, 1, payload);
    }

    private Object snapshot(boolean collection) {
        var relayer = ChainAddressRecord.builder().id(33L).tenantId(UUID.randomUUID()).mpcKeyId(UUID.randomUUID())
                .chain("ETH").address(RELAYER).walletRole("EIP7702_RELAYER").enabled(true).build();
        var authority = ChainAddressRecord.builder().id(11L).tenantId(tenant).mpcKeyId(UUID.randomUUID())
                .chain("ETH").address(AUTHORITY).enabled(true).build();
        var config = new Evm7702ConfigRepository.Configuration(UUID.randomUUID(), "ETH", "sepolia", BigInteger.valueOf(11155111),
                1, AUTHORITY, HASH, HOT, HASH, HOT, HASH, RELAYER, 33L, "ACTIVE", 10, 2_000_000,
                new BigDecimal("0.8"), new BigDecimal("1.2"), 120, 3, true, true, 1000, 10);
        Instant deadline = Instant.parse("2030-01-01T00:00:00Z");
        BigDecimal amount = new BigDecimal("1.000000000000000001");
        BigInteger atomic = new BigInteger("1000000000000000001");
        if (collection) return new Evm7702CollectionCoordinator.Batch(id, tenant, "ETH", "sepolia", "ETH", NATIVE, 18,
                HOT, HASH, deadline, config, relayer, List.of(new Evm7702CollectionCoordinator.ClaimedItem(
                        1L, "fixed-collection", tenant, UUID.randomUUID(), AUTHORITY, HOT, amount, atomic, authority)));
        return new Evm7702WithdrawalCoordinator.Batch(id, tenant, "ETH", "sepolia", "ETH", NATIVE, 18, HOT, HASH,
                deadline, authority, config, relayer, List.of(new Evm7702WithdrawalCoordinator.ClaimedItem(
                        1L, UUID.randomUUID(), UUID.randomUUID(), "fixed-withdrawal", AUTHORITY, amount,
                        new BigDecimal("0.001"), atomic, "fixed-debit-account", new byte[32])));
    }
    private static String table(boolean collection) { return collection ? "evm_collection_batch" : "evm_withdrawal_batch"; }
}
