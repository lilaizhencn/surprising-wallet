package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** withdrawal_order 单表仓储。 */
@Repository
public class WithdrawalOrderRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造提现订单仓储。 */
    public WithdrawalOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在提现订单。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from withdrawal_order where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在提现订单。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from withdrawal_order
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

    /** 创建非租户提现订单。 */
    public int create(String orderNo, long userId, String chain, String assetSymbol, String fromAddress,
                      String debitAccountId, String toAddress, BigDecimal amount, BigDecimal fee) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into withdrawal_order(order_no, user_id, chain, asset_symbol, from_address, debit_account_id,
                    to_address, amount, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                on conflict (chain, order_no) do nothing
                """, orderNo, userId, chain, assetSymbol, fromAddress, debitAccountId, toAddress, amount, fee, now, now);
    }

    /** 创建租户提现订单。 */
    public int createForTenant(UUID tenantId, String orderNo, long userId, String chain, String assetSymbol,
                               String fromAddress, String debitAccountId, String toAddress,
                               BigDecimal amount, BigDecimal fee) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                    debit_account_id, to_address, amount, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                on conflict (chain, order_no) do nothing
                """, tenantId, orderNo, userId, chain, assetSymbol, fromAddress, debitAccountId,
                toAddress, amount, fee, now, now);
    }

    /** 查询租户下一批待签名提现。 */
    public List<WithdrawalOrderRecord> listForSigning(String chain, String assetSymbol, int limit) {
        List<UUID> tenants = jdbc.queryForList("""
                select tenant_id from withdrawal_order
                 where tenant_id is not null and chain = ? and asset_symbol = ?
                   and status in ('FROZEN', 'RETRYING') order by id limit 1
                """, UUID.class, chain, assetSymbol);
        if (tenants.isEmpty()) {
            return List.of();
        }
        return listByTenantAndStatuses(tenants.getFirst(), chain, assetSymbol, limit);
    }

    /** 查询指定链的待签名提现。 */
    public List<WithdrawalOrderRecord> listForSigning(String chain, int limit) {
        return jdbc.query("""
                select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id,
                       to_address, amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order
                 where tenant_id is not null and chain = ? and status in ('FROZEN', 'RETRYING')
                 order by id limit ?
                """, (rs, rowNum) -> map(rs), chain, limit);
    }

    /** 查询指定状态的租户提现。 */
    public List<WithdrawalOrderRecord> listByStatus(String chain, String status, int limit) {
        return jdbc.query("""
                select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id,
                       to_address, amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order
                 where tenant_id is not null and chain = ? and status = ? order by id limit ?
                """, (rs, rowNum) -> map(rs), chain, status, limit);
    }

    /** 更新提现为签名中。 */
    public int claimSigning(UUID tenantId, String chain, String orderNo, String fromAddress) {
        return jdbc.update("""
                update withdrawal_order set status = 'SIGNING', from_address = coalesce(?, from_address),
                    error_message = null, updated_at = ?
                 where tenant_id = ? and chain = ? and order_no = ? and status in ('FROZEN', 'RETRYING')
                """, fromAddress, Timestamp.from(Instant.now()), tenantId, chain, orderNo);
    }

    /** 更新非租户提现为签名中。 */
    public int claimSigning(String chain, String orderNo, String fromAddress) {
        return jdbc.update("""
                update withdrawal_order set status = 'SIGNING', from_address = coalesce(?, from_address),
                    error_message = null, updated_at = ?
                 where chain = ? and order_no = ? and status in ('FROZEN', 'RETRYING')
                """, fromAddress, Timestamp.from(Instant.now()), chain, orderNo);
    }

    /** 标记过期签名提现为广播未知。 */
    public int markStaleSigningUnknown(String chain, Instant before) {
        return jdbc.update("""
                update withdrawal_order set status = 'BROADCAST_UNKNOWN',
                    error_message = 'signing state expired before a tx hash was recorded; manual chain audit required',
                    updated_at = ?
                 where tenant_id is not null and chain = ? and status = 'SIGNING'
                   and tx_hash is null and updated_at < ?
                """, Timestamp.from(Instant.now()), chain, Timestamp.from(before));
    }

    /** 标记提现已发送。 */
    public int markSent(UUID tenantId, String chain, String orderNo, String fromAddress, String txHash) {
        return jdbc.update("""
                update withdrawal_order set status = 'SENT', from_address = coalesce(?, from_address),
                    tx_hash = ?, error_message = null, updated_at = ?
                 where tenant_id = ? and chain = ? and order_no = ? and status = 'SIGNING' and tx_hash is null
                """, fromAddress, txHash, Timestamp.from(Instant.now()), tenantId, chain, orderNo);
    }

    /** 标记非租户提现已发送。 */
    public int markSent(String chain, String orderNo, String fromAddress, String txHash) {
        return jdbc.update("""
                update withdrawal_order set status = 'SENT', from_address = coalesce(?, from_address),
                    tx_hash = ?, error_message = null, updated_at = ?
                 where chain = ? and order_no = ? and status = 'SIGNING' and tx_hash is null
                """, fromAddress, txHash, Timestamp.from(Instant.now()), chain, orderNo);
    }

    /** 标记提现广播未知。 */
    public int markBroadcastUnknown(UUID tenantId, String chain, String orderNo,
                                    String fromAddress, String errorMessage) {
        return jdbc.update("""
                update withdrawal_order set status = 'BROADCAST_UNKNOWN', from_address = coalesce(?, from_address),
                    error_message = ?, updated_at = ?
                 where tenant_id = ? and chain = ? and order_no = ? and status = 'SIGNING' and tx_hash is null
                """, fromAddress, errorMessage, Timestamp.from(Instant.now()), tenantId, chain, orderNo);
    }

    /** 标记非租户提现广播未知。 */
    public int markBroadcastUnknown(String chain, String orderNo, String fromAddress, String errorMessage) {
        return jdbc.update("""
                update withdrawal_order set status = 'BROADCAST_UNKNOWN', from_address = coalesce(?, from_address),
                    error_message = ?, updated_at = ?
                 where chain = ? and order_no = ? and status = 'SIGNING' and tx_hash is null
                """, fromAddress, errorMessage, Timestamp.from(Instant.now()), chain, orderNo);
    }

    /** 更新租户提现状态。 */
    public int updateStatus(UUID tenantId, String chain, String orderNo, String status,
                            String fromAddress, String txHash, String errorMessage) {
        return jdbc.update("""
                update withdrawal_order set status = ?, from_address = coalesce(?, from_address),
                    tx_hash = coalesce(?, tx_hash), error_message = ?, updated_at = now()
                 where tenant_id = ? and chain = ? and order_no = ?
                """, status, fromAddress, txHash, errorMessage, tenantId, chain, orderNo);
    }

    /** 更新非租户提现状态。 */
    public int updateStatus(String chain, String orderNo, String status,
                            String fromAddress, String txHash, String errorMessage) {
        return jdbc.update("""
                update withdrawal_order set status = ?, from_address = coalesce(?, from_address),
                    tx_hash = coalesce(?, tx_hash), error_message = ?, updated_at = now()
                 where chain = ? and order_no = ?
                """, status, fromAddress, txHash, errorMessage, chain, orderNo);
    }

    /** 标记租户提现已确认。 */
    public int markConfirmed(UUID tenantId, String chain, String orderNo, String txHash) {
        return jdbc.update("""
                update withdrawal_order set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                 where tenant_id = ? and chain = ? and order_no = ?
                   and status in ('SENT', 'CONFIRMING') and tx_hash = ?
                """, txHash, Timestamp.from(Instant.now()), tenantId, chain, orderNo, txHash);
    }

    /** 标记非租户提现已确认。 */
    public int markConfirmed(String chain, String orderNo, String txHash) {
        return jdbc.update("""
                update withdrawal_order set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                 where chain = ? and order_no = ? and status in ('SENT', 'CONFIRMING') and tx_hash = ?
                """, txHash, Timestamp.from(Instant.now()), chain, orderNo, txHash);
    }

    /** 查询提现状态。 */
    public Optional<String> findStatus(String chain, String orderNo) {
        return jdbc.queryForList("select status from withdrawal_order where chain = ? and order_no = ?",
                String.class, chain, orderNo).stream().findFirst();
    }

    /** 查询提现交易哈希。 */
    public Optional<String> findTxHash(String chain, String orderNo, UUID tenantId) {
        String predicate = tenantId == null ? "chain = ? and order_no = ?" : "tenant_id = ? and chain = ? and order_no = ?";
        Object[] args = tenantId == null ? new Object[]{chain, orderNo} : new Object[]{tenantId, chain, orderNo};
        return jdbc.queryForList("select tx_hash from withdrawal_order where " + predicate
                        + " and tx_hash is not null", String.class, args).stream().findFirst();
    }

    /** 查询提现租户。 */
    public Optional<UUID> findTenant(String chain, String orderNo) {
        return jdbc.queryForList("""
                select tenant_id from withdrawal_order where chain = ? and order_no = ? and tenant_id is not null
                """, UUID.class, chain, orderNo).stream().findFirst();
    }

    /** 查询非租户条件的提现订单。 */
    public Optional<WithdrawalOrderRecord> find(String chain, String orderNo, UUID tenantId) {
        String predicate = tenantId == null ? "chain = ? and order_no = ?" : "tenant_id = ? and chain = ? and order_no = ?";
        Object[] args = tenantId == null ? new Object[]{chain, orderNo} : new Object[]{tenantId, chain, orderNo};
        String sql = """
                select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id,
                       to_address, amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order where 1 = 1 and """ + " " + predicate;
        return jdbc.query(sql,
                (rs, rowNum) -> map(rs), args).stream().findFirst();
    }

    /** 查询待 EVM 批量提现的订单，仅锁定提现订单单表行。 */
    public List<Map<String, Object>> listClaimable(String chain, int limit) {
        return jdbc.queryForList("""
                select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                       to_address, amount, fee, debit_account_id, status, created_at
                  from withdrawal_order
                 where tenant_id is not null and chain = ? and status in ('FROZEN', 'RETRYING')
                 order by id limit ? for update skip locked
                """, chain, Math.min(Math.max(limit, 1), 500));
    }

    /** 按租户和主键查询提现订单单表字段。 */
    public Optional<Map<String, Object>> findById(UUID tenantId, long id) {
        return jdbc.queryForList("""
                select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                       to_address, amount, fee, debit_account_id, status, tx_hash, error_message
                  from withdrawal_order where tenant_id = ? and id = ?
                """, tenantId, id).stream().findFirst();
    }

    /** 按主键领取提现订单。 */
    public int claimSigning(UUID tenantId, long id) {
        return jdbc.update("""
                update withdrawal_order set status = 'SIGNING', error_message = null, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('FROZEN', 'RETRYING')
                """, tenantId, id);
    }

    /** 按主键更新提现订单为已发送。 */
    public int markSent(UUID tenantId, long id, String txHash) {
        return jdbc.update("""
                update withdrawal_order set status = 'SENT', tx_hash = ?, error_message = null, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SIGNING' and tx_hash is null
                """, txHash, tenantId, id);
    }

    /** 按主键更新提现订单为重试中。 */
    public int markRetrying(UUID tenantId, long id, String error) {
        return jdbc.update("""
                update withdrawal_order set status = 'RETRYING', tx_hash = null, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('SIGNING', 'SENT')
                """, error, tenantId, id);
    }

    /** 按主键更新提现订单为失败。 */
    public int markFailed(UUID tenantId, long id, String error) {
        return jdbc.update("""
                update withdrawal_order set status = 'FAILED', error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SENT'
                """, error, tenantId, id);
    }

    /** 按租户和状态查询提现订单。 */
    private List<WithdrawalOrderRecord> listByTenantAndStatuses(UUID tenantId, String chain,
                                                                 String assetSymbol, int limit) {
        return jdbc.query("""
                select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id,
                       to_address, amount, fee, tx_hash, status, error_message, created_at, updated_at
                  from withdrawal_order
                 where tenant_id = ? and chain = ? and asset_symbol = ?
                   and status in ('FROZEN', 'RETRYING') order by id limit ?
                """, (rs, rowNum) -> map(rs), tenantId, chain, assetSymbol, limit);
    }
    /** 映射提现订单。 */
    private static WithdrawalOrderRecord map(ResultSet rs) throws SQLException {
        return WithdrawalOrderRecord.builder().id(rs.getLong("id"))
                .tenantId(rs.getObject("tenant_id", UUID.class)).orderNo(rs.getString("order_no"))
                .userId(rs.getLong("user_id")).chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol")).fromAddress(rs.getString("from_address"))
                .debitAccountId(rs.getString("debit_account_id")).toAddress(rs.getString("to_address"))
                .amount(rs.getBigDecimal("amount")).fee(rs.getBigDecimal("fee"))
                .txHash(rs.getString("tx_hash")).status(rs.getString("status"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant())
                .build();
    }

}
