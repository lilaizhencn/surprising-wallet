package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

}
