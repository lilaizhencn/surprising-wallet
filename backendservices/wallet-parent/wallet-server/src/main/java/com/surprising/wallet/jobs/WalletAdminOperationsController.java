package com.surprising.wallet.web.controller;

import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/wallet/v1/admin/operations")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.OPTIONS}
)
public class WalletAdminOperationsController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final JdbcTemplate jdbcTemplate;

    @Value("${SW_WALLET_ADMIN_USERNAME:${sw.wallet.admin.username:}}")
    private String adminUsername;

    @Value("${SW_WALLET_ADMIN_PASSWORD:${sw.wallet.admin.password:}}")
    private String adminPassword;

    public WalletAdminOperationsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/overview")
    public ResponseResult<Map<String, Object>> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "windowHours", defaultValue = "24") Integer windowHours,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit) {
        requireAdmin(authorization);
        String normalizedChain = normalizeOptionalIdentifier(chain, "chain");
        String normalizedAsset = normalizeOptionalIdentifier(assetSymbol, "assetSymbol");
        int safeWindow = normalizeWindowHours(windowHours);
        int safeLimit = normalizeLimit(limit);

        Map<String, Object> payload = orderedMap();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("filters", row(
                "chain", normalizedChain,
                "assetSymbol", normalizedAsset,
                "windowHours", safeWindow,
                "limit", safeLimit));
        payload.put("totals", operationTotals(normalizedChain, normalizedAsset, safeWindow));
        payload.put("chainStatuses", chainStatuses(normalizedChain, safeLimit));
        payload.put("ledgerByAsset", ledgerByAsset(normalizedChain, normalizedAsset, safeLimit));
        payload.put("hotWallets", hotWallets(normalizedChain, normalizedAsset, safeLimit));
        payload.put("scanHeights", scanHeights(normalizedChain, safeLimit));
        payload.put("exceptionSummary", exceptionSummary(normalizedChain, normalizedAsset, safeWindow, safeLimit));
        return ResultUtils.success(payload);
    }

    @GetMapping("/addresses")
    public ResponseResult<Map<String, Object>> addresses(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "walletRole", required = false) String walletRole,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(authorization);
        int safeLimit = normalizeLimit(limit);
        AdminCursorPage.SortSpec sortSpec = updatedAtSort("a.updated_at", "a.id", sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select a.id, a.chain, a.asset_symbol, a.account_id, a.user_id, a.biz, a.address_index,
                       a.address, a.owner_address, a.wallet_role, a.enabled,
                       coalesce(b.available_balance, 0) as available_balance,
                       coalesce(b.locked_balance, 0) as locked_balance,
                       coalesce(b.total_balance, 0) as total_balance,
                       a.created_at, a.updated_at
                  from chain_address a
                  left join ledger_balance b
                    on b.chain = a.chain
                   and b.asset_symbol = a.asset_symbol
                   and b.account_id = a.account_id
                 where 1 = 1
                """);
        addTextFilter(sql, args, "a.chain", normalizeOptionalIdentifier(chain, "chain"));
        addTextFilter(sql, args, "a.asset_symbol", normalizeOptionalIdentifier(assetSymbol, "assetSymbol"));
        addTextFilter(sql, args, "a.wallet_role", normalizeOptionalIdentifier(walletRole, "walletRole"));
        if (userId != null) {
            sql.append(" and a.user_id = ?");
            args.add(userId);
        }
        if (enabled != null) {
            sql.append(" and a.enabled = ?");
            args.add(enabled);
        }
        String normalizedAddress = normalizeOptionalText(address);
        if (normalizedAddress != null) {
            sql.append(" and (a.address = ? or a.owner_address = ? or a.account_id = ?)");
            args.add(normalizedAddress);
            args.add(normalizedAddress);
            args.add(normalizedAddress);
        }
        AdminCursorPage.addSeekCondition(sql, args, sortSpec, decodedCursor);
        sql.append(" order by ").append(AdminCursorPage.orderBy(sortSpec)).append(" limit ?");
        args.add(safeLimit + 1);
        return ResultUtils.success(AdminCursorPage.page("addresses", queryRows(sql.toString(), args.toArray()),
                safeLimit, sortSpec));
    }

    @GetMapping("/balances")
    public ResponseResult<Map<String, Object>> balances(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "nonZeroOnly", defaultValue = "true") Boolean nonZeroOnly,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(authorization);
        int safeLimit = normalizeLimit(limit);
        AdminCursorPage.SortSpec sortSpec = updatedAtSort("b.updated_at", "b.id", sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select b.id, b.chain, b.asset_symbol, b.account_id,
                       a.user_id, a.biz, a.address, a.owner_address, a.wallet_role, a.enabled,
                       b.available_balance, b.locked_balance, b.total_balance,
                       b.created_at, b.updated_at
                  from ledger_balance b
                  left join chain_address a
                    on a.chain = b.chain
                   and a.asset_symbol = b.asset_symbol
                   and a.account_id = b.account_id
                 where 1 = 1
                """);
        addTextFilter(sql, args, "b.chain", normalizeOptionalIdentifier(chain, "chain"));
        addTextFilter(sql, args, "b.asset_symbol", normalizeOptionalIdentifier(assetSymbol, "assetSymbol"));
        if (userId != null) {
            sql.append(" and a.user_id = ?");
            args.add(userId);
        }
        if (Boolean.TRUE.equals(nonZeroOnly)) {
            sql.append(" and b.total_balance <> 0");
        }
        AdminCursorPage.addSeekCondition(sql, args, sortSpec, decodedCursor);
        sql.append(" order by ").append(AdminCursorPage.orderBy(sortSpec)).append(" limit ?");
        args.add(safeLimit + 1);
        return ResultUtils.success(AdminCursorPage.page("balances", queryRows(sql.toString(), args.toArray()),
                safeLimit, sortSpec));
    }

    @GetMapping("/exceptions")
    public ResponseResult<Map<String, Object>> exceptions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(authorization);
        String normalizedEventType = normalizeOptionalEventType(eventType);
        String normalizedChain = normalizeOptionalIdentifier(chain, "chain");
        String normalizedAsset = normalizeOptionalIdentifier(assetSymbol, "assetSymbol");
        String normalizedStatus = normalizeOptionalIdentifier(status, "status");
        int safeLimit = normalizeLimit(limit);
        AdminCursorPage.SortSpec sortSpec = updatedAtSort("updated_at", "cursor_id", "cursor_id", sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedEventType);
        args.add(normalizedEventType);
        args.add(normalizedChain);
        args.add(normalizedChain);
        args.add(normalizedAsset);
        args.add(normalizedAsset);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        StringBuilder sql = new StringBuilder("""
                select *
                  from (
                        select 'DEPOSIT' as event_type,
                               d.id as event_id,
                               d.id * 3 + 1 as cursor_id,
                               null::varchar as order_no,
                               a.user_id,
                               d.chain,
                               d.asset_symbol,
                               d.tx_hash,
                               d.from_address,
                               d.to_address,
                               d.amount,
                               null::numeric as fee,
                               d.status,
                               case when d.credited = false then 'not credited' else null end as reason,
                               d.credited,
                               d.confirmations,
                               d.block_height,
                               d.created_at,
                               d.updated_at
                          from deposit_record d
                          left join chain_address a
                            on a.chain = d.chain
                           and a.asset_symbol = d.asset_symbol
                           and a.address = d.to_address
                         where d.credited = false
                            or d.status in ('FAILED', 'REJECTED', 'BROADCAST_UNKNOWN')
                        union all
                        select 'WITHDRAWAL' as event_type,
                               w.id as event_id,
                               w.id * 3 + 2 as cursor_id,
                               w.order_no,
                               w.user_id,
                               w.chain,
                               w.asset_symbol,
                               w.tx_hash,
                               w.from_address,
                               w.to_address,
                               w.amount,
                               w.fee,
                               w.status,
                               w.error_message as reason,
                               null::boolean as credited,
                               null::integer as confirmations,
                               null::bigint as block_height,
                               w.created_at,
                               w.updated_at
                          from withdrawal_order w
                         where w.status in ('PENDING_REVIEW', 'FAILED', 'BROADCAST_UNKNOWN', 'REJECTED')
                        union all
                        select 'COLLECTION' as event_type,
                               c.id as event_id,
                               c.id * 3 + 3 as cursor_id,
                               c.collection_no as order_no,
                               null::bigint as user_id,
                               c.chain,
                               c.asset_symbol,
                               c.tx_hash,
                               c.from_address,
                               c.to_address,
                               c.amount,
                               c.fee,
                               c.status,
                               c.error_message as reason,
                               null::boolean as credited,
                               null::integer as confirmations,
                               null::bigint as block_height,
                               c.created_at,
                               c.updated_at
                          from collection_record c
                         where c.status in ('CREATED', 'SIGNING', 'SENT', 'FAILED', 'BROADCAST_UNKNOWN')
                       ) events
                 where (cast(? as text) is null or event_type = ?)
                   and (cast(? as text) is null or chain = ?)
                   and (cast(? as text) is null or asset_symbol = ?)
                   and (cast(? as text) is null or status = ?)
                """);
        AdminCursorPage.addSeekCondition(sql, args, sortSpec, decodedCursor);
        sql.append(" order by ").append(AdminCursorPage.orderBy(sortSpec)).append(" limit ?");
        args.add(safeLimit + 1);
        return ResultUtils.success(AdminCursorPage.page("events", queryRows(sql.toString(), args.toArray()),
                safeLimit, sortSpec, "cursor_id"));
    }

    private Map<String, Object> operationTotals(String chain, String assetSymbol, int windowHours) {
        return queryOne("""
                with params as (
                    select cast(? as text) as chain,
                           cast(? as text) as asset_symbol,
                           cast(? as integer) as window_hours
                )
                select (select count(*) from chain_profile p
                         where p.enabled = true and (params.chain is null or p.chain = params.chain)) as enabled_chain_count,
                       (select count(*) from chain_rpc_node r
                         where r.enabled = true and (params.chain is null or r.chain = params.chain)) as enabled_rpc_node_count,
                       (select count(*) from chain_asset a
                         where a.active = true
                           and (params.chain is null or a.chain = params.chain)
                           and (params.asset_symbol is null or a.symbol = params.asset_symbol)) as active_asset_count,
                       (select count(*) from token_config t
                         where t.enabled = true
                           and (params.chain is null or t.chain = params.chain)
                           and (params.asset_symbol is null or t.symbol = params.asset_symbol)) as enabled_token_count,
                       (select count(*) from chain_address a
                         where a.user_id > 0
                           and (params.chain is null or a.chain = params.chain)
                           and (params.asset_symbol is null or a.asset_symbol = params.asset_symbol)) as user_address_count,
                       (select count(*) from chain_address a
                         where a.user_id = 0 and a.biz = 0
                           and (params.chain is null or a.chain = params.chain)
                           and (params.asset_symbol is null or a.asset_symbol = params.asset_symbol)) as hot_wallet_count,
                       (select coalesce(sum(b.available_balance), 0) from ledger_balance b
                         where (params.chain is null or b.chain = params.chain)
                           and (params.asset_symbol is null or b.asset_symbol = params.asset_symbol)) as available_balance,
                       (select coalesce(sum(b.locked_balance), 0) from ledger_balance b
                         where (params.chain is null or b.chain = params.chain)
                           and (params.asset_symbol is null or b.asset_symbol = params.asset_symbol)) as locked_balance,
                       (select coalesce(sum(b.total_balance), 0) from ledger_balance b
                         where (params.chain is null or b.chain = params.chain)
                           and (params.asset_symbol is null or b.asset_symbol = params.asset_symbol)) as total_balance,
                       (select count(*) from deposit_record d
                         where d.created_at >= now() - (params.window_hours * interval '1 hour')
                           and d.credited = false
                           and (params.chain is null or d.chain = params.chain)
                           and (params.asset_symbol is null or d.asset_symbol = params.asset_symbol)) as pending_credit_deposit_count,
                       (select count(*) from withdrawal_order w
                         where w.created_at >= now() - (params.window_hours * interval '1 hour')
                           and w.status = 'PENDING_REVIEW'
                           and (params.chain is null or w.chain = params.chain)
                           and (params.asset_symbol is null or w.asset_symbol = params.asset_symbol)) as pending_review_withdrawal_count,
                       (select count(*) from withdrawal_order w
                         where w.created_at >= now() - (params.window_hours * interval '1 hour')
                           and w.status in ('FAILED', 'REJECTED', 'BROADCAST_UNKNOWN')
                           and (params.chain is null or w.chain = params.chain)
                           and (params.asset_symbol is null or w.asset_symbol = params.asset_symbol)) as exception_withdrawal_count
                  from params
                """, chain, assetSymbol, windowHours);
    }

    private List<Map<String, Object>> chainStatuses(String chain, int limit) {
        return queryRows("""
                select p.chain, p.network, p.family, p.native_symbol, p.enabled,
                       p.scan_enabled, p.withdraw_enabled, p.collection_enabled, p.transfer_enabled,
                       coalesce(r.enabled_rpc_nodes, 0) as enabled_rpc_nodes,
                       coalesce(a.active_assets, 0) as active_assets,
                       h.scanner_name as latest_scanner_name,
                       h.best_height,
                       h.safe_height,
                       h.status as scan_status,
                       h.updated_at as scan_updated_at
                  from chain_profile p
                  left join (
                      select chain, count(*) as enabled_rpc_nodes
                        from chain_rpc_node
                       where enabled = true
                       group by chain
                  ) r on r.chain = p.chain
                  left join (
                      select chain, count(*) as active_assets
                        from chain_asset
                       where active = true
                       group by chain
                  ) a on a.chain = p.chain
                  left join lateral (
                      select scanner_name, best_height, safe_height, status, updated_at
                        from chain_scan_height
                       where chain = p.chain
                       order by updated_at desc, id desc
                       limit 1
                  ) h on true
                 where (cast(? as text) is null or p.chain = ?)
                 order by p.enabled desc, p.chain
                 limit ?
                """, chain, chain, limit);
    }

    private List<Map<String, Object>> ledgerByAsset(String chain, String assetSymbol, int limit) {
        return queryRows("""
                select b.chain, b.asset_symbol,
                       count(*) as account_count,
                       coalesce(sum(b.available_balance), 0) as available_balance,
                       coalesce(sum(b.locked_balance), 0) as locked_balance,
                       coalesce(sum(b.total_balance), 0) as total_balance,
                       max(b.updated_at) as last_balance_at
                  from ledger_balance b
                 where (cast(? as text) is null or b.chain = ?)
                   and (cast(? as text) is null or b.asset_symbol = ?)
                 group by b.chain, b.asset_symbol
                 order by total_balance desc, b.chain, b.asset_symbol
                 limit ?
                """, chain, chain, assetSymbol, assetSymbol, limit);
    }

    private List<Map<String, Object>> hotWallets(String chain, String assetSymbol, int limit) {
        return queryRows("""
                select a.id, a.chain, a.asset_symbol, a.account_id, a.address, a.owner_address,
                       a.wallet_role, a.enabled,
                       coalesce(b.available_balance, 0) as available_balance,
                       coalesce(b.locked_balance, 0) as locked_balance,
                       coalesce(b.total_balance, 0) as total_balance,
                       a.updated_at
                  from chain_address a
                  left join ledger_balance b
                    on b.chain = a.chain
                   and b.asset_symbol = a.asset_symbol
                   and b.account_id = a.account_id
                 where a.user_id = 0
                   and a.biz = 0
                   and (cast(? as text) is null or a.chain = ?)
                   and (cast(? as text) is null or a.asset_symbol = ?)
                 order by a.chain, a.asset_symbol, a.wallet_role, a.address_index
                 limit ?
                """, chain, chain, assetSymbol, assetSymbol, limit);
    }

    private List<Map<String, Object>> scanHeights(String chain, int limit) {
        return queryRows("""
                select chain, scanner_name, best_height, safe_height, status, created_at, updated_at
                  from chain_scan_height
                 where (cast(? as text) is null or chain = ?)
                 order by updated_at desc, id desc
                 limit ?
                """, chain, chain, limit);
    }

    private List<Map<String, Object>> exceptionSummary(String chain, String assetSymbol, int windowHours, int limit) {
        return queryRows("""
                with params as (
                    select cast(? as text) as chain,
                           cast(? as text) as asset_symbol,
                           cast(? as integer) as window_hours
                ),
                events as (
                    select 'DEPOSIT' as event_type, d.chain, d.asset_symbol, d.status, d.amount
                      from deposit_record d, params p
                     where d.created_at >= now() - (p.window_hours * interval '1 hour')
                       and d.credited = false
                       and (p.chain is null or d.chain = p.chain)
                       and (p.asset_symbol is null or d.asset_symbol = p.asset_symbol)
                    union all
                    select 'WITHDRAWAL' as event_type, w.chain, w.asset_symbol, w.status, w.amount
                      from withdrawal_order w, params p
                     where w.created_at >= now() - (p.window_hours * interval '1 hour')
                       and w.status in ('PENDING_REVIEW', 'FAILED', 'BROADCAST_UNKNOWN', 'REJECTED')
                       and (p.chain is null or w.chain = p.chain)
                       and (p.asset_symbol is null or w.asset_symbol = p.asset_symbol)
                    union all
                    select 'COLLECTION' as event_type, c.chain, c.asset_symbol, c.status, c.amount
                      from collection_record c, params p
                     where c.created_at >= now() - (p.window_hours * interval '1 hour')
                       and c.status in ('CREATED', 'SIGNING', 'SENT', 'FAILED', 'BROADCAST_UNKNOWN')
                       and (p.chain is null or c.chain = p.chain)
                       and (p.asset_symbol is null or c.asset_symbol = p.asset_symbol)
                )
                select event_type, chain, asset_symbol, status, count(*) as count, coalesce(sum(amount), 0) as amount
                  from events
                 group by event_type, chain, asset_symbol, status
                 order by count desc, event_type, chain, asset_symbol, status
                 limit ?
                """, chain, assetSymbol, windowHours, limit);
    }

    private AdminCursorPage.SortSpec updatedAtSort(String timestampColumn, String idColumn, String sort) {
        return updatedAtSort(timestampColumn, idColumn, "id", sort);
    }

    private AdminCursorPage.SortSpec updatedAtSort(String timestampColumn, String idColumn,
                                                   String idResponseKey, String sort) {
        List<AdminCursorPage.SortSpec> allowed = AdminCursorPage.timestampSorts(
                "updatedAt", timestampColumn, "updated_at", idColumn, idResponseKey);
        return AdminCursorPage.parseSort(sort, allowed.getFirst(), allowed);
    }

    private void addTextFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sql.append(" and ").append(column).append(" = ?");
        args.add(value);
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = queryRows(sql, args);
        return rows.isEmpty() ? orderedMap() : rows.get(0);
    }

    private List<Map<String, Object>> queryRows(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args).stream()
                .map(this::sanitizeRow)
                .toList();
    }

    private Map<String, Object> sanitizeRow(Map<String, Object> source) {
        Map<String, Object> row = orderedMap();
        source.forEach((key, value) -> row.put(key, sanitizeValue(value)));
        return row;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant().toString();
        }
        return value;
    }

    private String normalizeOptionalIdentifier(String value, String name) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{1,96}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + name);
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeOptionalEventType(String value) {
        String normalized = normalizeOptionalIdentifier(value, "eventType");
        if (normalized == null) {
            return null;
        }
        if (!List.of("DEPOSIT", "WITHDRAWAL", "COLLECTION").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid eventType");
        }
        return normalized;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeWindowHours(Integer windowHours) {
        if (windowHours == null || windowHours <= 0) {
            return 24;
        }
        return Math.min(windowHours, 24 * 90);
    }

    private void requireAdmin(String authorization) {
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet admin credentials are not configured");
        }
        if (authorization == null || !authorization.startsWith("Basic ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet admin authentication required");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring("Basic ".length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid wallet admin authorization");
        }
        int split = decoded.indexOf(':');
        if (split <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid wallet admin authorization");
        }
        String user = decoded.substring(0, split);
        String password = decoded.substring(split + 1);
        if (!constantEquals(user, adminUsername) || !constantEquals(password, adminPassword)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid wallet admin credentials");
        }
    }

    private boolean constantEquals(String actual, String expected) {
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actualBytes, expectedBytes);
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = orderedMap();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }
}
