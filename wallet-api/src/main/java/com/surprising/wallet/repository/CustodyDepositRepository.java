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

    public UUID credit(UUID tenantId, UUID addressId, long depositRecordId, String chain,
                       String symbol, String txHash, long logIndex, java.math.BigDecimal amount) {
        return jdbc.queryForObject("""
                insert into custody_deposit(id, tenant_id, custody_address_id, deposit_record_id,
                    chain, asset_symbol, tx_hash, log_index, amount, status, credited_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', now())
                on conflict (tenant_id, deposit_record_id) do update set
                    status = excluded.status, credited_at = excluded.credited_at, updated_at = now()
                returning id
                """, UUID.class, UUID.randomUUID(), tenantId, addressId, depositRecordId,
                chain, symbol, txHash, logIndex, amount);
    }

    public java.util.Optional<Projection> lockProjection(UUID tenantId, long depositRecordId) {
        return jdbc.query("""
                select id, custody_address_id from custody_deposit
                 where tenant_id = ? and deposit_record_id = ? for update
                """, (rs, rowNum) -> new Projection(rs.getObject("id", UUID.class),
                rs.getObject("custody_address_id", UUID.class)), tenantId, depositRecordId).stream().findFirst();
    }

    public int markReorged(UUID tenantId, UUID id) {
        return jdbc.update("""
                update custody_deposit set status = 'REORGED', updated_at = now() where id = ? and tenant_id = ?
                """, id, tenantId);
    }

    public record Projection(UUID id, UUID addressId) { }

}
