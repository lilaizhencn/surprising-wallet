package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainJdbcRepositoryCollectionCandidateTest {
    private static final UUID TENANT_A = UUID.fromString("77020000-0000-0000-0000-000000000101");
    private static final UUID TENANT_B = UUID.fromString("77020000-0000-0000-0000-000000000102");

    @Test
    void collectionCandidatesUseGroupedRowsAndPreserveEligibilityAmountAndOrdering() {
        ChainJdbcRepository repository = new ChainJdbcRepository(new CollectionJdbcTemplate());

        List<CollectionCandidateRecord> candidates = repository.listCollectableLedgerBalances(
                "ETH", BigDecimal.ZERO, 20);

        assertEquals(2, candidates.size());
        assertCandidate(candidates.get(0), TENANT_B, "0xdef", "15");
        assertCandidate(candidates.get(1), TENANT_A, "0xAbC", "7");
    }

    private void assertCandidate(CollectionCandidateRecord candidate, UUID tenantId,
                                 String address, String amount) {
        assertEquals(tenantId, candidate.getTenantId());
        assertEquals(address, candidate.getAddress());
        assertEquals(0, new BigDecimal(amount).compareTo(candidate.getAmount()));
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static final class CollectionJdbcTemplate extends JdbcTemplate {
        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            String query = sql.replaceAll("\\s+", " ").toLowerCase();
            if (query.contains(" from deposit_record ")) {
                assertTrue(query.contains("sum(amount)"),
                        "deposit collection balance query must aggregate amounts in PostgreSQL");
                assertTrue(query.contains("group by"),
                        "deposit collection balance query must return one row per balance key");
                return List.of(
                        row("tenant_id", TENANT_A, "asset_symbol", "ETH", "to_address", "0xabc",
                                "amount", new BigDecimal("10")),
                        row("tenant_id", TENANT_A, "asset_symbol", "USDT", "to_address", "0xabc",
                                "amount", new BigDecimal("50")),
                        row("tenant_id", TENANT_B, "asset_symbol", "ETH", "to_address", "0xdef",
                                "amount", new BigDecimal("15")),
                        row("tenant_id", TENANT_A, "asset_symbol", "ETH", "to_address", "0xhot",
                                "amount", new BigDecimal("100")),
                        row("tenant_id", TENANT_A, "asset_symbol", "ETH", "to_address", "0xmissing",
                                "amount", new BigDecimal("30")));
            }
            if (query.contains(" from collection_record ")) {
                assertTrue(query.contains("filter"),
                        "collection balance query must exclude FAILED amounts during aggregation");
                assertTrue(query.contains("bool_or"),
                        "collection balance query must aggregate pending-state suppression");
                return List.of(
                        row("tenant_id", TENANT_A, "asset_symbol", "ETH", "from_address", "0xabc",
                                "amount", new BigDecimal("3"), "pending", false),
                        row("tenant_id", TENANT_A, "asset_symbol", "USDT", "from_address", "0xabc",
                                "amount", new BigDecimal("5"), "pending", true));
            }
            if (query.contains(" from chain_asset ")) {
                return List.of(
                        row("symbol", "ETH", "native_asset", true, "min_transfer", BigDecimal.ONE),
                        row("symbol", "USDT", "native_asset", false, "min_transfer", new BigDecimal("5")));
            }
            if (query.contains(" from custody_address ")) {
                return List.of(
                        row("chain_address_id", 1L, "id", UUID.fromString(
                                "77020000-0000-0000-0000-000000000111")),
                        row("chain_address_id", 2L, "id", UUID.fromString(
                                "77020000-0000-0000-0000-000000000112")),
                        row("chain_address_id", 3L, "id", UUID.fromString(
                                "77020000-0000-0000-0000-000000000113")));
            }
            if (query.contains(" from chain_address ")) {
                assertTrue(query.contains("tenant_id is not null"));
                assertTrue(query.contains("wallet_role = 'deposit'"));
                assertTrue(query.contains("user_id <> 0"));
                return List.of(
                        address(1L, TENANT_A, "0xAbC", 1L, 4L),
                        address(2L, TENANT_B, "0xdef", 2L, 2L),
                        address(3L, TENANT_A, "0xhot", 0L, 0L),
                        address(4L, TENANT_A, "0xmissing", 3L, 3L));
            }
            throw new AssertionError("unexpected SQL: " + query);
        }

        private Map<String, Object> address(long id, UUID tenantId, String address,
                                            long userId, long addressIndex) {
            return row("id", id, "tenant_id", tenantId, "asset_symbol", "ETH",
                    "account_id", address.toLowerCase(), "user_id", userId, "biz", 0,
                    "address_index", addressIndex, "address", address,
                    "owner_address", address, "wallet_role", "DEPOSIT", "enabled", true);
        }
    }
}
