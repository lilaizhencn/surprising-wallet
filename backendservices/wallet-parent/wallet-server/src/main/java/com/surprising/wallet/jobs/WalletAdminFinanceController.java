package com.surprising.wallet.web.controller;

import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/wallet/v1/admin/finance")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
public class WalletAdminFinanceController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ChainJdbcRepository repository;

    @Value("${SW_WALLET_ADMIN_USERNAME:${sw.wallet.admin.username:}}")
    private String adminUsername;

    @Value("${SW_WALLET_ADMIN_PASSWORD:${sw.wallet.admin.password:}}")
    private String adminPassword;

    public WalletAdminFinanceController(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
    }

    @GetMapping("/summary")
    public ResponseResult<Map<String, Object>> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "windowHours", defaultValue = "24") Integer windowHours,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        requireAdmin(authorization);
        int safeLimit = normalizeLimit(limit);
        Map<String, Object> payload = orderedMap();
        String normalizedChain = normalizeOptional(chain);
        String normalizedAsset = normalizeOptional(assetSymbol);
        int safeWindow = normalizeWindowHours(windowHours);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("filters", row(
                "chain", normalizedChain,
                "assetSymbol", normalizedAsset,
                "windowHours", safeWindow));
        payload.put("totals", queryOne("""
                with params as (
                    select cast(? as text) as chain,
                           cast(? as text) as asset_symbol,
                           cast(? as integer) as window_hours
                ),
                deposits as (
                    select count(*) as count,
                           coalesce(sum(amount), 0) as amount,
                           count(*) filter (where credited = true) as credited_count,
                           coalesce(sum(amount) filter (where credited = true), 0) as credited_amount
                      from deposit_record d, params p
                     where d.created_at >= now() - (p.window_hours * interval '1 hour')
                       and (p.chain is null or d.chain = p.chain)
                       and (p.asset_symbol is null or d.asset_symbol = p.asset_symbol)
                ),
                withdrawals as (
                    select count(*) as count,
                           coalesce(sum(amount), 0) as amount,
                           coalesce(sum(fee), 0) as fee,
                           count(*) filter (where status in ('PENDING_REVIEW', 'FROZEN', 'SIGNING', 'SENT', 'CONFIRMING', 'BROADCAST_UNKNOWN')) as in_flight_count,
                           coalesce(sum(amount) filter (where status in ('PENDING_REVIEW', 'FROZEN', 'SIGNING', 'SENT', 'CONFIRMING', 'BROADCAST_UNKNOWN')), 0) as in_flight_amount,
                           count(*) filter (where status in ('FAILED', 'REJECTED', 'BROADCAST_UNKNOWN')) as exception_count,
                           count(*) filter (where status = 'PENDING_REVIEW') as pending_review_count
                      from withdrawal_order w, params p
                     where w.created_at >= now() - (p.window_hours * interval '1 hour')
                       and (p.chain is null or w.chain = p.chain)
                       and (p.asset_symbol is null or w.asset_symbol = p.asset_symbol)
                ),
                balances as (
                    select coalesce(sum(available_balance), 0) as available_balance,
                           coalesce(sum(locked_balance), 0) as locked_balance,
                           coalesce(sum(total_balance), 0) as total_balance
                      from ledger_balance b, params p
                     where (p.chain is null or b.chain = p.chain)
                       and (p.asset_symbol is null or b.asset_symbol = p.asset_symbol)
                )
                select d.count as deposit_count,
                       d.amount as deposit_amount,
                       d.credited_count as credited_deposit_count,
                       d.credited_amount as credited_deposit_amount,
                       w.count as withdrawal_count,
                       w.amount as withdrawal_amount,
                       w.fee as withdrawal_fee,
                       w.in_flight_count as in_flight_withdrawal_count,
                       w.in_flight_amount as in_flight_withdrawal_amount,
                       w.exception_count as withdrawal_exception_count,
                       w.pending_review_count as pending_review_count,
                       b.available_balance,
                       b.locked_balance,
                       b.total_balance
                  from deposits d cross join withdrawals w cross join balances b
                """, normalizedChain, normalizedAsset, safeWindow));
        payload.put("byAsset", queryRows("""
                with params as (
                    select cast(? as text) as chain,
                           cast(? as text) as asset_symbol,
                           cast(? as integer) as window_hours
                ),
                deposit_asset as (
                    select d.chain, d.asset_symbol,
                           count(*) as deposit_count,
                           coalesce(sum(d.amount), 0) as deposit_amount
                      from deposit_record d, params p
                     where d.created_at >= now() - (p.window_hours * interval '1 hour')
                       and (p.chain is null or d.chain = p.chain)
                       and (p.asset_symbol is null or d.asset_symbol = p.asset_symbol)
                     group by d.chain, d.asset_symbol
                ),
                withdrawal_asset as (
                    select w.chain, w.asset_symbol,
                           count(*) as withdrawal_count,
                           coalesce(sum(w.amount), 0) as withdrawal_amount,
                           count(*) filter (where w.status = 'PENDING_REVIEW') as pending_review_count
                      from withdrawal_order w, params p
                     where w.created_at >= now() - (p.window_hours * interval '1 hour')
                       and (p.chain is null or w.chain = p.chain)
                       and (p.asset_symbol is null or w.asset_symbol = p.asset_symbol)
                     group by w.chain, w.asset_symbol
                )
                select coalesce(d.chain, w.chain) as chain,
                       coalesce(d.asset_symbol, w.asset_symbol) as asset_symbol,
                       coalesce(d.deposit_count, 0) as deposit_count,
                       coalesce(d.deposit_amount, 0) as deposit_amount,
                       coalesce(w.withdrawal_count, 0) as withdrawal_count,
                       coalesce(w.withdrawal_amount, 0) as withdrawal_amount,
                       coalesce(w.pending_review_count, 0) as pending_review_count
                  from deposit_asset d
                  full outer join withdrawal_asset w
                    on d.chain = w.chain and d.asset_symbol = w.asset_symbol
                 order by chain, asset_symbol
                 limit ?
                """, normalizedChain, normalizedAsset, safeWindow, safeLimit));
        payload.put("recentDeposits", deposits(normalizedChain, normalizedAsset, null, null, safeLimit));
        payload.put("recentWithdrawals", withdrawals(normalizedChain, normalizedAsset, null, null, null, safeLimit));
        return ResultUtils.success(payload);
    }

    @GetMapping("/deposits")
    public ResponseResult<Map<String, Object>> deposits(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "credited", required = false) Boolean credited,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(authorization);
        return ResultUtils.success(depositsPage(
                normalizeOptional(chain),
                normalizeOptional(assetSymbol),
                normalizeOptional(status),
                credited,
                normalizeLimit(limit),
                cursor,
                sort));
    }

    @GetMapping("/withdrawals")
    public ResponseResult<Map<String, Object>> withdrawals(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(authorization);
        return ResultUtils.success(withdrawalsPage(
                normalizeOptional(chain),
                normalizeOptional(assetSymbol),
                normalizeOptional(status),
                userId,
                normalizeOptional(orderNo),
                normalizeLimit(limit),
                cursor,
                sort));
    }

    @GetMapping("/withdrawal-reviews")
    public ResponseResult<Map<String, Object>> withdrawalReviews(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "assetSymbol", required = false) String assetSymbol,
            @RequestParam(value = "decision", required = false) String decision,
            @RequestParam(value = "adminUserId", required = false) Long adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(authorization);
        return ResultUtils.success(withdrawalReviewsPage(
                normalizeOptional(chain),
                normalizeOptional(assetSymbol),
                normalizeOptional(decision),
                adminUserId,
                userId,
                normalizeOptionalOrderNo(orderNo),
                normalizeLimit(limit),
                cursor,
                sort));
    }

    @PostMapping("/withdrawals/{chain}/{orderNo}/approve")
    @Transactional(rollbackFor = Throwable.class)
    public ResponseResult<Map<String, Object>> approveWithdrawal(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsernameHeader,
            @PathVariable("chain") String chain,
            @PathVariable("orderNo") String orderNo,
            @RequestBody(required = false) ReviewRequest request) {
        requireAdmin(authorization);
        String normalizedChain = requireIdentifier(chain, "chain");
        String normalizedOrderNo = requireOrderNo(orderNo);
        Map<String, Object> current = requireWithdrawal(normalizedChain, normalizedOrderNo);
        requireStatus(current, "PENDING_REVIEW");
        int updated = jdbcTemplate.update("""
                update withdrawal_order
                   set status = 'FROZEN',
                       error_message = null,
                       updated_at = now()
                 where chain = ? and order_no = ? and status = 'PENDING_REVIEW'
                """, normalizedChain, normalizedOrderNo);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "withdrawal state changed before approval");
        }
        Map<String, Object> updatedWithdrawal = requireWithdrawal(normalizedChain, normalizedOrderNo);
        Map<String, Object> review = recordWithdrawalReview(current, updatedWithdrawal, "APPROVED",
                adminUserId, adminUsernameHeader, reviewReason(request), null);
        Map<String, Object> payload = orderedMap();
        payload.put("decision", "APPROVED");
        payload.put("reviewer", reviewer(adminUserId, adminUsernameHeader));
        payload.put("reason", reviewReason(request));
        payload.put("review", review);
        payload.put("withdrawal", updatedWithdrawal);
        return ResultUtils.success(payload);
    }

    @PostMapping("/withdrawals/{chain}/{orderNo}/reject")
    @Transactional(rollbackFor = Throwable.class)
    public ResponseResult<Map<String, Object>> rejectWithdrawal(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsernameHeader,
            @PathVariable("chain") String chain,
            @PathVariable("orderNo") String orderNo,
            @RequestBody(required = false) ReviewRequest request) {
        requireAdmin(authorization);
        String normalizedChain = requireIdentifier(chain, "chain");
        String normalizedOrderNo = requireOrderNo(orderNo);
        Map<String, Object> current = requireWithdrawal(normalizedChain, normalizedOrderNo);
        requireStatus(current, "PENDING_REVIEW");
        String assetSymbol = String.valueOf(current.get("asset_symbol"));
        String debitAccountId = String.valueOf(current.get("debit_account_id"));
        BigDecimal amount = decimal(current.get("amount")).add(decimal(current.get("fee")));
        if (debitAccountId == null || debitAccountId.isBlank() || "null".equalsIgnoreCase(debitAccountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "withdrawal has no debit account to release");
        }
        if (!repository.releaseLockedBalance(normalizedChain, assetSymbol, debitAccountId, amount)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "unable to release locked withdrawal balance");
        }
        String reason = reviewReason(request);
        int updated = jdbcTemplate.update("""
                update withdrawal_order
                   set status = 'REJECTED',
                       error_message = ?,
                       updated_at = now()
                 where chain = ? and order_no = ? and status = 'PENDING_REVIEW'
                """, reviewMessage("rejected", adminUserId, adminUsernameHeader, reason), normalizedChain, normalizedOrderNo);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "withdrawal state changed before rejection");
        }
        Map<String, Object> updatedWithdrawal = requireWithdrawal(normalizedChain, normalizedOrderNo);
        Map<String, Object> review = recordWithdrawalReview(current, updatedWithdrawal, "REJECTED",
                adminUserId, adminUsernameHeader, reason, amount);
        Map<String, Object> payload = orderedMap();
        payload.put("decision", "REJECTED");
        payload.put("reviewer", reviewer(adminUserId, adminUsernameHeader));
        payload.put("reason", reason);
        payload.put("releasedAmount", amount);
        payload.put("review", review);
        payload.put("withdrawal", updatedWithdrawal);
        return ResultUtils.success(payload);
    }

    private List<Map<String, Object>> deposits(String chain, String assetSymbol, String status,
                                               Boolean credited, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, chain, asset_symbol, tx_hash, log_index, from_address, to_address, contract_address,
                       amount, block_height, confirmations, status, credited, credited_at, created_at, updated_at
                  from deposit_record
                 where 1 = 1
                """);
        addTextFilter(sql, args, "chain", chain);
        addTextFilter(sql, args, "asset_symbol", assetSymbol);
        addTextFilter(sql, args, "status", status);
        if (credited != null) {
            sql.append(" and credited = ?");
            args.add(credited);
        }
        sql.append(" order by updated_at desc, id desc limit ?");
        args.add(limit);
        return queryRows(sql.toString(), args.toArray());
    }

    private Map<String, Object> depositsPage(String chain, String assetSymbol, String status,
                                             Boolean credited, int limit, String cursor, String sort) {
        AdminCursorPage.SortSpec sortSpec = updatedAtSort("updated_at", "id", sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, chain, asset_symbol, tx_hash, log_index, from_address, to_address, contract_address,
                       amount, block_height, confirmations, status, credited, credited_at, created_at, updated_at
                  from deposit_record
                 where 1 = 1
                """);
        addTextFilter(sql, args, "chain", chain);
        addTextFilter(sql, args, "asset_symbol", assetSymbol);
        addTextFilter(sql, args, "status", status);
        if (credited != null) {
            sql.append(" and credited = ?");
            args.add(credited);
        }
        AdminCursorPage.addSeekCondition(sql, args, sortSpec, decodedCursor);
        sql.append(" order by ").append(AdminCursorPage.orderBy(sortSpec)).append(" limit ?");
        args.add(limit + 1);
        return AdminCursorPage.page("deposits", queryRows(sql.toString(), args.toArray()), limit, sortSpec);
    }

    private List<Map<String, Object>> withdrawals(String chain, String assetSymbol, String status,
                                                  Long userId, String orderNo, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                       amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order
                 where 1 = 1
                """);
        addTextFilter(sql, args, "chain", chain);
        addTextFilter(sql, args, "asset_symbol", assetSymbol);
        addTextFilter(sql, args, "status", status);
        if (userId != null) {
            sql.append(" and user_id = ?");
            args.add(userId);
        }
        if (orderNo != null) {
            sql.append(" and order_no = ?");
            args.add(orderNo);
        }
        sql.append(" order by updated_at desc, id desc limit ?");
        args.add(limit);
        return queryRows(sql.toString(), args.toArray());
    }

    private Map<String, Object> withdrawalsPage(String chain, String assetSymbol, String status,
                                                Long userId, String orderNo, int limit, String cursor, String sort) {
        AdminCursorPage.SortSpec sortSpec = updatedAtSort("updated_at", "id", sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                       amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order
                 where 1 = 1
                """);
        addTextFilter(sql, args, "chain", chain);
        addTextFilter(sql, args, "asset_symbol", assetSymbol);
        addTextFilter(sql, args, "status", status);
        if (userId != null) {
            sql.append(" and user_id = ?");
            args.add(userId);
        }
        if (orderNo != null) {
            sql.append(" and order_no = ?");
            args.add(orderNo);
        }
        AdminCursorPage.addSeekCondition(sql, args, sortSpec, decodedCursor);
        sql.append(" order by ").append(AdminCursorPage.orderBy(sortSpec)).append(" limit ?");
        args.add(limit + 1);
        return AdminCursorPage.page("withdrawals", queryRows(sql.toString(), args.toArray()), limit, sortSpec);
    }

    private List<Map<String, Object>> withdrawalReviews(String chain, String assetSymbol, String decision,
                                                        Long adminUserId, Long userId, String orderNo, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select review_id, withdrawal_id, order_no, user_id, chain, asset_symbol, amount, fee,
                       from_address, debit_account_id, to_address, previous_status, next_status, decision,
                       admin_user_id, admin_username, reason, released_amount, created_at
                  from withdrawal_review_audit
                 where 1 = 1
                """);
        addTextFilter(sql, args, "chain", chain);
        addTextFilter(sql, args, "asset_symbol", assetSymbol);
        addTextFilter(sql, args, "decision", decision);
        if (adminUserId != null) {
            sql.append(" and admin_user_id = ?");
            args.add(adminUserId);
        }
        if (userId != null) {
            sql.append(" and user_id = ?");
            args.add(userId);
        }
        if (orderNo != null) {
            sql.append(" and order_no = ?");
            args.add(orderNo);
        }
        sql.append(" order by created_at desc, review_id desc limit ?");
        args.add(limit);
        return queryRows(sql.toString(), args.toArray());
    }

    private Map<String, Object> withdrawalReviewsPage(String chain, String assetSymbol, String decision,
                                                      Long adminUserId, Long userId, String orderNo, int limit,
                                                      String cursor, String sort) {
        AdminCursorPage.SortSpec sortSpec = createdAtSort("created_at", "review_id", sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select review_id, withdrawal_id, order_no, user_id, chain, asset_symbol, amount, fee,
                       from_address, debit_account_id, to_address, previous_status, next_status, decision,
                       admin_user_id, admin_username, reason, released_amount, created_at
                  from withdrawal_review_audit
                 where 1 = 1
                """);
        addTextFilter(sql, args, "chain", chain);
        addTextFilter(sql, args, "asset_symbol", assetSymbol);
        addTextFilter(sql, args, "decision", decision);
        if (adminUserId != null) {
            sql.append(" and admin_user_id = ?");
            args.add(adminUserId);
        }
        if (userId != null) {
            sql.append(" and user_id = ?");
            args.add(userId);
        }
        if (orderNo != null) {
            sql.append(" and order_no = ?");
            args.add(orderNo);
        }
        AdminCursorPage.addSeekCondition(sql, args, sortSpec, decodedCursor);
        sql.append(" order by ").append(AdminCursorPage.orderBy(sortSpec)).append(" limit ?");
        args.add(limit + 1);
        return AdminCursorPage.page("reviews", queryRows(sql.toString(), args.toArray()), limit, sortSpec);
    }

    private AdminCursorPage.SortSpec updatedAtSort(String timestampColumn, String idColumn, String sort) {
        List<AdminCursorPage.SortSpec> allowed = AdminCursorPage.timestampSorts(
                "updatedAt", timestampColumn, "updated_at", idColumn, idColumn);
        return AdminCursorPage.parseSort(sort, allowed.getFirst(), allowed);
    }

    private AdminCursorPage.SortSpec createdAtSort(String timestampColumn, String idColumn, String sort) {
        List<AdminCursorPage.SortSpec> allowed = AdminCursorPage.timestampSorts(
                "createdAt", timestampColumn, "created_at", idColumn, idColumn);
        return AdminCursorPage.parseSort(sort, allowed.getFirst(), allowed);
    }

    private Map<String, Object> recordWithdrawalReview(Map<String, Object> previous,
                                                       Map<String, Object> updated,
                                                       String decision,
                                                       String adminUserId,
                                                       String adminUsernameHeader,
                                                       String reason,
                                                       BigDecimal releasedAmount) {
        Long reviewerUserId = parseOptionalAdminUserId(adminUserId);
        String reviewerUsername = adminUsernameHeader == null || adminUsernameHeader.isBlank()
                ? null
                : adminUsernameHeader.trim();
        return queryOne("""
                insert into withdrawal_review_audit (
                    withdrawal_id, order_no, user_id, chain, asset_symbol, amount, fee,
                    from_address, debit_account_id, to_address, previous_status, next_status, decision,
                    admin_user_id, admin_username, reason, released_amount, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                returning review_id, withdrawal_id, order_no, user_id, chain, asset_symbol, amount, fee,
                          from_address, debit_account_id, to_address, previous_status, next_status, decision,
                          admin_user_id, admin_username, reason, released_amount, created_at
                """,
                longValue(previous.get("id")),
                String.valueOf(previous.get("order_no")),
                longValue(previous.get("user_id")),
                String.valueOf(previous.get("chain")),
                String.valueOf(previous.get("asset_symbol")),
                decimal(previous.get("amount")),
                decimal(previous.get("fee")),
                nullableString(previous.get("from_address")),
                nullableString(previous.get("debit_account_id")),
                String.valueOf(previous.get("to_address")),
                String.valueOf(previous.get("status")),
                String.valueOf(updated.get("status")),
                decision,
                reviewerUserId,
                reviewerUsername,
                reason == null || reason.isBlank() ? null : reason,
                releasedAmount);
    }

    private void addTextFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sql.append(" and ").append(column).append(" = ?");
        args.add(value);
    }

    private Map<String, Object> requireWithdrawal(String chain, String orderNo) {
        List<Map<String, Object>> rows = queryRows("""
                select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                       amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order
                 where chain = ? and order_no = ?
                 limit 1
                """, chain, orderNo);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "withdrawal order not found");
        }
        return rows.get(0);
    }

    private void requireStatus(Map<String, Object> withdrawal, String status) {
        if (!Objects.equals(String.valueOf(withdrawal.get("status")), status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "withdrawal must be " + status + " but was " + withdrawal.get("status"));
        }
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

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String requireIdentifier(String value, String name) {
        String normalized = normalizeOptional(value);
        if (normalized == null || !normalized.matches("[A-Z0-9_.:-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + name);
        }
        return normalized;
    }

    private String requireOrderNo(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid withdrawal orderNo");
        }
        return value.trim();
    }

    private String normalizeOptionalOrderNo(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireOrderNo(value);
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

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Long parseOptionalAdminUserId(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(adminUserId.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid X-Admin-User-Id", ex);
        }
    }

    private String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return "null".equalsIgnoreCase(text) ? null : text;
    }

    private String reviewReason(ReviewRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            return "";
        }
        return request.reason().trim();
    }

    private String reviewMessage(String decision, String adminUserId, String adminUsernameHeader, String reason) {
        String reviewer = adminUsernameHeader == null || adminUsernameHeader.isBlank()
                ? adminUserId
                : adminUsernameHeader;
        return "admin " + decision + " by " + (reviewer == null || reviewer.isBlank() ? "unknown" : reviewer)
                + (reason == null || reason.isBlank() ? "" : ": " + reason);
    }

    private Map<String, Object> reviewer(String adminUserId, String adminUsernameHeader) {
        return row("adminUserId", adminUserId, "username", adminUsernameHeader);
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

    private static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = orderedMap();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    public record ReviewRequest(String reason) {
    }
}
