package com.surprising.wallet.web.controller;

import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only operational dashboard. Configuration is managed by the platform console. */
@Slf4j
@RestController
@RequestMapping("/wallet/v1")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.OPTIONS}
)
public class WalletDashboardController {
    private static final int DEFAULT_LIMIT = 200;
    private final JdbcTemplate jdbc;

    public WalletDashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/dashboard")
    public ResponseResult<Map<String, Object>> dashboard(
            @RequestParam(value = "limit", defaultValue = "200") Integer limit) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("generatedAt", Instant.now().toString());
            payload.put("runtime", runtimeSnapshot(normalizeLimit(limit)));
            return ResultUtils.success(payload);
        } catch (Exception e) {
            log.error("wallet dashboard query failed", e);
            return ResultUtils.failure("查询钱包项目总览失败");
        }
    }

    @GetMapping("/dashboard/address-transactions")
    public ResponseResult<List<Map<String, Object>>> addressTransactions(
            @RequestParam("address") String address,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit) {
        try {
            List<Object> args = new ArrayList<>();
            String chainFilter = "";
            String chainValue = null;
            if (chain != null && !chain.isBlank()) {
                chainFilter = " and chain = ?";
                chainValue = chain.toUpperCase(Locale.ROOT);
            }
            addAddressArgs(args, address, chainValue);
            addAddressArgs(args, address, chainValue);
            addAddressArgs(args, address, chainValue);
            args.add(normalizeLimit(limit));
            String sql = """
                    select * from (
                      select 'DEPOSIT' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                             amount, null::numeric as fee, status, confirmations, block_height, credited, created_at, updated_at
                        from deposit_record where (from_address = ? or to_address = ?) %s
                      union all
                      select 'WITHDRAW', id, chain, asset_symbol, tx_hash, from_address, to_address,
                             amount, fee, status, null::integer, null::bigint, null::boolean, created_at, updated_at
                        from withdrawal_order where (from_address = ? or to_address = ?) %s
                      union all
                      select 'COLLECTION', id, chain, asset_symbol, tx_hash, from_address, to_address,
                             amount, fee, status, null::integer, null::bigint, null::boolean, created_at, updated_at
                        from collection_record where (from_address = ? or to_address = ?) %s
                    ) t order by updated_at desc limit ?
                    """.formatted(chainFilter, chainFilter, chainFilter);
            return ResultUtils.success(queryRows(sql, args.toArray()));
        } catch (Exception e) {
            log.error("address transaction query failed address={} chain={}", address, chain, e);
            return ResultUtils.failure("查询地址交易记录失败");
        }
    }

    private Map<String, Object> runtimeSnapshot(int limit) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("counts", Map.of(
                "enabledChains", count("select count(*) from chain_profile where enabled = true"),
                "enabledTokens", count("select count(*) from token_config where enabled = true"),
                "enabledRpcNodes", count("select count(*) from chain_rpc_node where enabled = true"),
                "addresses", count("select count(*) from chain_address"),
                "deposits", count("select count(*) from deposit_record"),
                "withdrawals", count("select count(*) from withdrawal_order")));
        runtime.put("chainProfiles", queryRows("""
                select id, chain, network, family, native_symbol, enabled,
                       scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled, updated_at
                  from chain_profile order by chain, network
                """));
        runtime.put("tokens", queryRows("""
                select id, chain, network, symbol, coalesce(token_standard, standard) as standard,
                       contract_address, decimals, enabled, collect_enabled, updated_at
                  from token_config order by chain, symbol
                """));
        runtime.put("rpcNodes", queryRows("""
                select id, chain, network, environment, node_label, purpose, connection_type,
                       rpc_url, auth_type, priority, enabled, updated_at
                  from chain_rpc_node order by chain, network, environment, purpose, priority
                """));
        runtime.put("transactions", recentTransactions(limit));
        return runtime;
    }

    private List<Map<String, Object>> recentTransactions(int limit) {
        return queryRows("""
                select * from (
                  select 'DEPOSIT' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                         amount, null::numeric as fee, status, created_at, updated_at from deposit_record
                  union all
                  select 'WITHDRAW', id, chain, asset_symbol, tx_hash, from_address, to_address,
                         amount, fee, status, created_at, updated_at from withdrawal_order
                  union all
                  select 'COLLECTION', id, chain, asset_symbol, tx_hash, from_address, to_address,
                         amount, fee, status, created_at, updated_at from collection_record
                ) t order by updated_at desc limit ?
                """, limit);
    }

    private List<Map<String, Object>> queryRows(String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args).stream().map(this::sanitize).toList();
        } catch (DataAccessException e) {
            log.warn("dashboard query skipped: {}", sql.replaceAll("\\s+", " ").trim(), e);
            return List.of();
        }
    }

    private long count(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (DataAccessException e) {
            log.warn("dashboard count skipped: {}", sql, e);
            return 0;
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, sanitizeValue(key, value)));
        return result;
    }

    private Object sanitizeValue(String key, Object value) {
        if (value == null) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        if (Objects.equals(lower, "api_key") || Objects.equals(lower, "password")
                || lower.contains("private") || lower.contains("secret")) {
            return String.valueOf(value).isBlank() ? "" : "***configured***";
        }
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof OffsetDateTime offset) return offset.toInstant().toString();
        return value;
    }

    private static void addAddressArgs(List<Object> args, String address, String chain) {
        args.add(address);
        args.add(address);
        if (chain != null) args.add(chain);
    }

    private static int normalizeLimit(Integer limit) {
        return limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
    }
}
