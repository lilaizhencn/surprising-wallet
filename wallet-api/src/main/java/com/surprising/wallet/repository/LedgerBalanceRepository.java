package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ledger_balance 单表仓储。 */
@Repository
public class LedgerBalanceRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造账本余额仓储。 */
    public LedgerBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在账本数据。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from ledger_balance where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在账本数据。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from ledger_balance
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

    /** 查询租户指定账户中满足金额要求的账本余额。 */
    public List<Map<String, Object>> listAvailable(UUID tenantId, String chain, String symbol,
                                                    String accountId, BigDecimal requiredAmount) {
        return jdbc.queryForList("""
                select id, account_id, available_balance
                  from ledger_balance
                 where tenant_id = ? and lower(chain) = lower(?)
                   and lower(asset_symbol) = lower(?) and lower(account_id) = lower(?)
                   and available_balance >= ?
                 order by available_balance desc, id
                """, tenantId, chain, symbol, accountId, requiredAmount);
    }

    /** 查询租户全部账本余额，供服务层完成账户和资产组合。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, tenant_id, chain, asset_symbol, account_id,
                       available_balance, locked_balance, total_balance
                  from ledger_balance
                 where tenant_id = ?
                """, tenantId);
    }

    /** 写入或更新账本余额。 */
    public int upsert(LedgerBalanceRecord record) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into ledger_balance(chain, asset_symbol, account_id, available_balance, locked_balance,
                                           total_balance, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, asset_symbol, account_id) do update set
                    available_balance = excluded.available_balance, locked_balance = excluded.locked_balance,
                    total_balance = excluded.total_balance, updated_at = excluded.updated_at
                """, record.getChain(), record.getAssetSymbol(), record.getAccountId(),
                record.getAvailableBalance(), record.getLockedBalance(), record.getTotalBalance(), now, now);
    }

    /** 为租户增加可用余额和总余额，租户归属不匹配时不更新。 */
    public int increment(UUID tenantId, String chain, String assetSymbol, String accountId, BigDecimal amount) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into ledger_balance(tenant_id, chain, asset_symbol, account_id, available_balance,
                                           locked_balance, total_balance, created_at, updated_at)
                values (?, ?, ?, ?, ?, 0, ?, ?, ?)
                on conflict (chain, asset_symbol, account_id) do update set
                    available_balance = ledger_balance.available_balance + excluded.available_balance,
                    total_balance = ledger_balance.total_balance + excluded.total_balance,
                    tenant_id = coalesce(ledger_balance.tenant_id, excluded.tenant_id),
                    updated_at = excluded.updated_at
                where ledger_balance.tenant_id is null or ledger_balance.tenant_id = excluded.tenant_id
                """, tenantId, chain, assetSymbol, accountId, amount, amount, now, now);
    }

    /** 扣减可用余额和总余额。 */
    public boolean debit(String chain, String assetSymbol, String accountId, BigDecimal amount, UUID tenantId) {
        String tenantClause = tenantId == null ? "" : " and tenant_id = ?";
        String sql = """
                update ledger_balance
                   set available_balance = available_balance - ?, total_balance = total_balance - ?, updated_at = ?
                 where chain = ? and asset_symbol = ? and account_id = ?
                   and available_balance >= ?
                """ + tenantClause;
        Object[] args = tenantId == null
                ? new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount}
                : new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount, tenantId};
        return jdbc.update(sql, args) == 1;
    }

    /** 冻结可用余额。 */
    public boolean freeze(String chain, String assetSymbol, String accountId, BigDecimal amount, UUID tenantId) {
        String tenantClause = tenantId == null ? "" : " and tenant_id = ?";
        String sql = """
                update ledger_balance
                   set available_balance = available_balance - ?, locked_balance = locked_balance + ?, updated_at = ?
                 where chain = ? and asset_symbol = ? and account_id = ? and available_balance >= ?
                """ + tenantClause;
        Object[] args = tenantId == null
                ? new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount}
                : new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount, tenantId};
        return jdbc.update(sql, args) == 1;
    }

    /** 释放已冻结余额。 */
    public boolean release(String chain, String assetSymbol, String accountId, BigDecimal amount, UUID tenantId) {
        String tenantClause = tenantId == null ? "" : " and tenant_id = ?";
        String sql = """
                update ledger_balance
                   set available_balance = available_balance + ?, locked_balance = locked_balance - ?, updated_at = ?
                 where chain = ? and asset_symbol = ? and account_id = ? and locked_balance >= ?
                """ + tenantClause;
        Object[] args = tenantId == null
                ? new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount}
                : new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount, tenantId};
        return jdbc.update(sql, args) == 1;
    }

    /** 结算已冻结扣款。 */
    public boolean settle(String chain, String assetSymbol, String accountId, BigDecimal amount, UUID tenantId) {
        String tenantClause = tenantId == null ? "" : " and tenant_id = ?";
        String sql = """
                update ledger_balance
                   set locked_balance = locked_balance - ?, total_balance = total_balance - ?, updated_at = ?
                 where chain = ? and asset_symbol = ? and account_id = ? and locked_balance >= ?
                """ + tenantClause;
        Object[] args = tenantId == null
                ? new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount}
                : new Object[]{amount, amount, Timestamp.from(Instant.now()), chain, assetSymbol, accountId, amount, tenantId};
        return jdbc.update(sql, args) == 1;
    }

    /** 结算 Gas 预留，并按实际费用返还差额或扣除超额费用。 */
    public boolean settleReserved(String chain, String assetSymbol, String accountId,
                                  BigDecimal reservedAmount, BigDecimal actualAmount, UUID tenantId) {
        String tenantClause = tenantId == null ? "" : " and tenant_id = ?";
        BigDecimal difference = reservedAmount.subtract(actualAmount);
        String sql;
        Object[] args;
        if (difference.signum() >= 0) {
            sql = """
                    update ledger_balance
                       set available_balance = available_balance + ?,
                           locked_balance = locked_balance - ?,
                           total_balance = total_balance - ?, updated_at = ?
                     where chain = ? and asset_symbol = ? and account_id = ?
                       and locked_balance >= ? and total_balance >= ?
                    """ + tenantClause;
            args = tenantId == null
                    ? new Object[]{difference, reservedAmount, actualAmount, Timestamp.from(Instant.now()),
                    chain, assetSymbol, accountId, reservedAmount, actualAmount}
                    : new Object[]{difference, reservedAmount, actualAmount, Timestamp.from(Instant.now()),
                    chain, assetSymbol, accountId, reservedAmount, actualAmount, tenantId};
        } else {
            BigDecimal extra = difference.negate();
            sql = """
                    update ledger_balance
                       set available_balance = available_balance - ?,
                           locked_balance = locked_balance - ?,
                           total_balance = total_balance - ?, updated_at = ?
                     where chain = ? and asset_symbol = ? and account_id = ?
                       and available_balance >= ? and locked_balance >= ? and total_balance >= ?
                    """ + tenantClause;
            args = tenantId == null
                    ? new Object[]{extra, reservedAmount, actualAmount, Timestamp.from(Instant.now()),
                    chain, assetSymbol, accountId, extra, reservedAmount, actualAmount}
                    : new Object[]{extra, reservedAmount, actualAmount, Timestamp.from(Instant.now()),
                    chain, assetSymbol, accountId, extra, reservedAmount, actualAmount, tenantId};
        }
        return jdbc.update(sql, args) == 1;
    }

    /** 查询单个账本余额。 */
    public java.util.Optional<LedgerBalanceRecord> find(String chain, String assetSymbol, String accountId) {
        return jdbc.query("""
                select id, chain, asset_symbol, account_id, available_balance, locked_balance, total_balance,
                       created_at, updated_at
                  from ledger_balance where chain = ? and asset_symbol = ? and account_id = ?
                """, (rs, rowNum) -> map(rs), chain, assetSymbol, accountId).stream().findFirst();
    }

    /** 按租户查询单个账本余额，防止同一账户文本跨租户串读。 */
    public java.util.Optional<LedgerBalanceRecord> find(UUID tenantId, String chain,
                                                         String assetSymbol, String accountId) {
        return jdbc.query("""
                select id, chain, asset_symbol, account_id, available_balance, locked_balance, total_balance,
                       created_at, updated_at
                  from ledger_balance
                 where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                """, (rs, rowNum) -> map(rs), tenantId, chain, assetSymbol, accountId).stream().findFirst();
    }

    /** 查询全部账本余额。 */
    public List<LedgerBalanceRecord> listAll() {
        return jdbc.query("""
                select id, chain, asset_symbol, account_id, available_balance, locked_balance, total_balance,
                       created_at, updated_at from ledger_balance order by chain, asset_symbol, account_id
                """, (rs, rowNum) -> map(rs));
    }

    /** 汇总总余额。 */
    public BigDecimal sumTotal(String chain, String assetSymbol) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(total_balance), 0) from ledger_balance where chain = ? and asset_symbol = ?
                """, BigDecimal.class, chain, assetSymbol);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 汇总可用余额。 */
    public BigDecimal sumAvailable(String chain, String assetSymbol) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(available_balance), 0) from ledger_balance where chain = ? and asset_symbol = ?
                """, BigDecimal.class, chain, assetSymbol);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 查询指定账本的可用余额并加锁。 */
    public BigDecimal findAvailableForUpdate(UUID tenantId, String chain, String assetSymbol, String accountId) {
        return jdbc.query("""
                select available_balance from ledger_balance
                 where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ? for update
                """, (rs, rowNum) -> rs.getBigDecimal(1), tenantId, chain, assetSymbol, accountId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /** 将数据库行转换为账本模型。 */
    private static LedgerBalanceRecord map(ResultSet rs) throws SQLException {
        return LedgerBalanceRecord.builder()
                .id(rs.getLong("id"))
                .chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol"))
                .accountId(rs.getString("account_id"))
                .availableBalance(rs.getBigDecimal("available_balance"))
                .lockedBalance(rs.getBigDecimal("locked_balance"))
                .totalBalance(rs.getBigDecimal("total_balance"))
                .createdAt(toInstant(rs.getTimestamp("created_at")))
                .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                .build();
    }

    /** 转换可空数据库时间。 */
    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

}
