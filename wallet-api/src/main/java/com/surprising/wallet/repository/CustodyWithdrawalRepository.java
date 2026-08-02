package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_withdrawal 单表仓储。 */
@Repository
public class CustodyWithdrawalRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管提现仓储。 */
    public CustodyWithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按订单查询托管提现单表字段。 */
    public List<Map<String, Object>> listByOrder(UUID tenantId, long withdrawalOrderId) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, order_no, external_reference,
                       chain, asset_symbol, to_address, amount, fee, status,
                       withdrawal_order_id
                  from custody_withdrawal
                 where tenant_id = ? and withdrawal_order_id = ?
                """, tenantId, withdrawalOrderId);
    }

    /** 按租户和主键查询托管提现记录。 */
    public List<Map<String, Object>> find(UUID tenantId, UUID id) {
        return jdbc.queryForList("""
                select id, tenant_id, chain, status
                  from custody_withdrawal where tenant_id = ? and id = ?
                """, tenantId, id);
    }

    /** 创建托管提现记录，订单主键由业务编排层解析后传入。 */
    public int insert(UUID id, UUID tenantId, UUID custodyAddressId, long withdrawalOrderId,
                      String orderNo, String externalReference, String idempotencyKey, String chain,
                      String assetSymbol, String toAddress, java.math.BigDecimal amount,
                      java.math.BigDecimal fee, String status, String createdByType, String createdById) {
        return jdbc.update("""
                insert into custody_withdrawal(id, tenant_id, custody_address_id, withdrawal_order_id,
                    order_no, external_reference, idempotency_key, chain, asset_symbol, to_address,
                    amount, fee, status, created_by_type, created_by_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, custodyAddressId, withdrawalOrderId, orderNo, externalReference,
                idempotencyKey, chain, assetSymbol, toAddress, amount, fee, status, createdByType, createdById);
    }

    /** 查询租户托管提现记录。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId, String chain, String assetSymbol,
                                                  String status, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, withdrawal_order_id, order_no, external_reference,
                       chain, asset_symbol, to_address, amount, fee, status, created_by_type, created_by_id,
                       created_at, updated_at
                  from custody_withdrawal where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                 order by created_at desc, id limit ? offset ?
                """, tenantId, chain, chain, assetSymbol, assetSymbol, status, status,
                Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 统计租户托管提现记录。 */
    public long countByTenant(UUID tenantId, String chain, String assetSymbol, String status) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_withdrawal where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                """, Long.class, tenantId, chain, chain, assetSymbol, assetSymbol, status, status);
        return count == null ? 0 : count;
    }

    /** 查询状态不一致的托管提现记录。 */
    public List<Map<String, Object>> listStatusChanges(int limit) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, withdrawal_order_id, order_no, external_reference,
                       chain, asset_symbol, to_address, amount, fee, status, created_by_type, created_by_id,
                       created_at, updated_at
                  from custody_withdrawal where status <> 'CONFIRMED' order by updated_at, id limit ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    /** 更新托管提现记录状态。 */
    public int updateStatus(UUID tenantId, UUID id, String status) {
        return jdbc.update("update custody_withdrawal set status = ?, updated_at = now() where tenant_id = ? and id = ?",
                status, tenantId, id);
    }

}
