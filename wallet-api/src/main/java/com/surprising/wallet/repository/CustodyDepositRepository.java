package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_deposit 单表仓储。 */
@Repository
public class CustodyDepositRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管充值单表仓储。 */
    public CustodyDepositRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询已确认充值的入账时间。 */
    public Timestamp findConfirmedAt(UUID tenantId, UUID custodyAddressId, String chain,
                                     String assetSymbol, String txHash) {
        List<Timestamp> rows = jdbc.queryForList("""
                select credited_at
                  from custody_deposit
                 where tenant_id = ? and custody_address_id = ?
                   and chain = ? and asset_symbol = ?
                   and lower(tx_hash) = lower(?) and status = 'CONFIRMED'
                 limit 1
                """, Timestamp.class, tenantId, custodyAddressId, chain, assetSymbol, txHash);
        return rows.stream().findFirst().orElse(null);
    }

    /** 查询租户托管充值记录。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId, String chain, String assetSymbol,
                                                  String status, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, chain, asset_symbol, tx_hash, log_index,
                       amount, status, credited_at, created_at, updated_at
                  from custody_deposit where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                 order by created_at desc, id limit ? offset ?
                """, tenantId, chain, chain, assetSymbol, assetSymbol, status, status,
                Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 统计租户托管充值记录。 */
    public long countByTenant(UUID tenantId, String chain, String assetSymbol, String status) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_deposit where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                """, Long.class, tenantId, chain, chain, assetSymbol, assetSymbol, status, status);
        return count == null ? 0 : count;
    }
}
