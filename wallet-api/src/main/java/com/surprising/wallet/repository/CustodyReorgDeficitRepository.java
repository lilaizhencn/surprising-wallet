package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** custody_reorg_deficit 单表仓储。 */
@Repository
public class CustodyReorgDeficitRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造重组赤字仓储。 */
    public CustodyReorgDeficitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询租户尚未弥补的重组赤字。 */
    public List<CustodyAssetDashboardRepository.ReorgDeficit> listOpen(UUID tenantId) {
        return jdbc.query("""
                select id, custody_address_id, chain, asset_symbol,
                       deficit_amount, recovered_amount, created_at
                  from custody_reorg_deficit
                 where tenant_id = ? and status = 'OPEN'
                 order by created_at desc
                """, (rs, rowNum) -> new CustodyAssetDashboardRepository.ReorgDeficit(
                rs.getObject("id", UUID.class),
                rs.getObject("custody_address_id", UUID.class),
                rs.getString("chain"), rs.getString("asset_symbol"),
                rs.getBigDecimal("deficit_amount"), rs.getBigDecimal("recovered_amount"),
                rs.getTimestamp("created_at").toInstant()), tenantId);
    }
}
