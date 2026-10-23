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

    /** 判断指定租户账户是否存在未弥补的重组赤字。 */
    public boolean existsOpen(UUID tenantId, String chain, String assetSymbol, String accountId) {
        return !jdbc.queryForList("""
                select id from custody_reorg_deficit
                 where tenant_id = ? and chain = ? and asset_symbol = ?
                   and account_id = ? and status = 'OPEN' limit 1
                """, tenantId, chain, assetSymbol, accountId).isEmpty();
    }

    public List<Deficit> lockOpenForAccount(UUID tenantId, String chain, String symbol, String accountId) {
        return jdbc.query("""
                select id, deficit_amount, recovered_amount from custody_reorg_deficit
                 where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ? and status = 'OPEN'
                 order by created_at, id for update
                """, (rs, rowNum) -> new Deficit(rs.getObject("id", UUID.class),
                rs.getBigDecimal("deficit_amount"), rs.getBigDecimal("recovered_amount")),
                tenantId, chain, symbol, accountId);
    }

    public int recover(UUID tenantId, UUID id, java.math.BigDecimal amount) {
        return jdbc.update("""
                update custody_reorg_deficit set recovered_amount = recovered_amount + ?,
                    status = case when recovered_amount + ? >= deficit_amount then 'RECOVERED' else 'OPEN' end,
                    updated_at = now() where id = ? and tenant_id = ?
                """, amount, amount, id, tenantId);
    }

    public UUID open(UUID tenantId, UUID addressId, long depositRecordId, String chain, String symbol,
                     String accountId, java.math.BigDecimal originalAmount, java.math.BigDecimal deficitAmount) {
        return jdbc.queryForObject("""
                insert into custody_reorg_deficit(id, tenant_id, custody_address_id, deposit_record_id,
                    chain, asset_symbol, account_id, original_amount, deficit_amount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, deposit_record_id) do update set
                    original_amount = excluded.original_amount, deficit_amount = excluded.deficit_amount,
                    recovered_amount = 0, status = 'OPEN', updated_at = now()
                returning id
                """, UUID.class, UUID.randomUUID(), tenantId, addressId, depositRecordId,
                chain, symbol, accountId, originalAmount, deficitAmount);
    }

    public record Deficit(UUID id, java.math.BigDecimal amount, java.math.BigDecimal recovered) { }

}
