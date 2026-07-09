package com.surprising.wallet.web.controller;

import com.surprising.commons.support.model.ResponseResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletAdminOperationsControllerTest {

    @Test
    void overviewAggregatesWalletOperations() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        WalletAdminOperationsController controller = controller(jdbcTemplate);

        ResponseResult<Map<String, Object>> response = controller.overview(auth(), "eth", "usdt", 24, 50);

        assertEquals(0, response.getCode());
        Map<String, Object> data = response.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("12.34", totals.get("available_balance"));
        assertEquals(1L, totals.get("pending_review_withdrawal_count"));
        assertFalse(list(data, "chainStatuses").isEmpty());
        assertFalse(list(data, "ledgerByAsset").isEmpty());
        assertFalse(list(data, "hotWallets").isEmpty());
        assertFalse(list(data, "exceptionSummary").isEmpty());
        assertTrue(jdbcTemplate.args.stream().anyMatch(args -> List.of(args).contains("ETH")));
        assertTrue(jdbcTemplate.args.stream().anyMatch(args -> List.of(args).contains("USDT")));
    }

    @Test
    void addressQueryRequiresWalletAdminAuth() {
        WalletAdminOperationsController controller = controller(new FakeJdbcTemplate());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.addresses(null, null, null, null, null, null, null, 100, null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void exceptionsRejectUnknownEventType() {
        WalletAdminOperationsController controller = controller(new FakeJdbcTemplate());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.exceptions(auth(), "BAD", null, null, null, 100, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void addressAndBalanceQueriesExposeSanitizedRows() {
        WalletAdminOperationsController controller = controller(new FakeJdbcTemplate());

        Map<String, Object> addressPage = controller.addresses(
                auth(), "eth", "usdt", 7L, "deposit", true, "0xabc", 10, null, "updatedAt.desc").getData();
        Map<String, Object> balancePage = controller.balances(
                auth(), "eth", "usdt", 7L, true, 10, null, "updatedAt.desc").getData();
        Map<String, Object> exceptionPage = controller.exceptions(
                auth(), "WITHDRAWAL", "eth", "usdt", "PENDING_REVIEW", 10, null, "updatedAt.desc").getData();
        List<Map<String, Object>> addresses = list(addressPage, "addresses");
        List<Map<String, Object>> balances = list(balancePage, "balances");
        List<Map<String, Object>> exceptions = list(exceptionPage, "events");

        assertEquals("1.25", addresses.getFirst().get("total_balance"));
        assertEquals("1.25", balances.getFirst().get("total_balance"));
        assertEquals("WITHDRAWAL", exceptions.getFirst().get("event_type"));
        assertEquals("updatedAt.desc", addressPage.get("sort"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    private static WalletAdminOperationsController controller(FakeJdbcTemplate jdbcTemplate) {
        WalletAdminOperationsController controller = new WalletAdminOperationsController(jdbcTemplate);
        setField(controller, "adminUsername", "admin");
        setField(controller, "adminPassword", "secret");
        return controller;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String auth() {
        String token = Base64.getEncoder().encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final List<Object[]> args = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.args.add(args);
            if (sql.contains("enabled_chain_count")) {
                return List.of(row(
                        "enabled_chain_count", 1L,
                        "enabled_rpc_node_count", 2L,
                        "active_asset_count", 3L,
                        "enabled_token_count", 1L,
                        "user_address_count", 4L,
                        "hot_wallet_count", 1L,
                        "available_balance", new BigDecimal("12.3400"),
                        "locked_balance", BigDecimal.ZERO,
                        "total_balance", new BigDecimal("12.3400"),
                        "pending_credit_deposit_count", 2L,
                        "pending_review_withdrawal_count", 1L,
                        "exception_withdrawal_count", 0L));
            }
            if (sql.contains("from chain_profile p")) {
                return List.of(row(
                        "chain", "ETH",
                        "network", "mainnet",
                        "family", "evm",
                        "native_symbol", "ETH",
                        "enabled", true,
                        "scan_enabled", true,
                        "withdraw_enabled", true,
                        "enabled_rpc_nodes", 2L));
            }
            if (sql.contains("group by b.chain, b.asset_symbol")) {
                return List.of(row(
                        "chain", "ETH",
                        "asset_symbol", "USDT",
                        "account_count", 2L,
                        "available_balance", new BigDecimal("12.3400"),
                        "locked_balance", BigDecimal.ZERO,
                        "total_balance", new BigDecimal("12.3400")));
            }
            if (sql.contains("a.user_id = 0")) {
                return List.of(addressRow(0L));
            }
            if (sql.contains("from chain_scan_height")) {
                return List.of(row(
                        "chain", "ETH",
                        "scanner_name", "evm",
                        "best_height", 100L,
                        "safe_height", 95L,
                        "status", "ACTIVE"));
            }
            if (sql.contains("events as")) {
                return List.of(row(
                        "event_type", "WITHDRAWAL",
                        "chain", "ETH",
                        "asset_symbol", "USDT",
                        "status", "PENDING_REVIEW",
                        "count", 1L,
                        "amount", new BigDecimal("1.2500")));
            }
            if (sql.contains("from chain_address a")) {
                return List.of(addressRow(7L));
            }
            if (sql.contains("from ledger_balance b")) {
                return List.of(addressRow(7L));
            }
            if (sql.contains("select *") && sql.contains("'WITHDRAWAL' as event_type")) {
                return List.of(row(
                        "event_type", "WITHDRAWAL",
                        "event_id", 9L,
                        "order_no", "WD-1",
                        "user_id", 7L,
                        "chain", "ETH",
                        "asset_symbol", "USDT",
                        "amount", new BigDecimal("1.2500"),
                        "status", "PENDING_REVIEW"));
            }
            return List.of();
        }

        private static Map<String, Object> addressRow(long userId) {
            return row(
                    "id", 1L,
                    "chain", "ETH",
                    "asset_symbol", "USDT",
                    "account_id", "0xabc",
                    "user_id", userId,
                    "address", "0xabc",
                        "wallet_role", "DEPOSIT",
                        "available_balance", new BigDecimal("1.2500"),
                        "locked_balance", BigDecimal.ZERO,
                    "total_balance", new BigDecimal("1.2500"),
                    "created_at", "2026-07-02T00:00:00Z",
                    "updated_at", "2026-07-02T00:01:00Z");
        }
    }
}
