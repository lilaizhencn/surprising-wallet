package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** custody_gas_usage 单表仓储。 */
@Repository
public class CustodyGasUsageRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Gas 使用记录仓储。 */
    public CustodyGasUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询操作对应的 Gas 使用记录。 */
    public Optional<Map<String, Object>> find(UUID tenantId, String operationType, UUID operationId) {
        return jdbc.queryForList("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where tenant_id = ? and operation_type = ? and operation_id = ?
                """, tenantId, operationType, operationId).stream().findFirst();
    }

    /** 按托管提现主键查询 Gas 使用记录。 */
    public Optional<Map<String, Object>> findByOperationId(UUID operationId) {
        return jdbc.queryForList("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where operation_id = ? order by created_at desc limit 1
                """, operationId).stream().findFirst();
    }

    /** 创建或复用预留记录。 */
    public int insert(UUID id, UUID tenantId, UUID gasAccountId, String operationType, UUID operationId,
                      String referenceNo, String chain, String nativeSymbol, BigDecimal reservedAmount,
                      String pricingSource) {
        return jdbc.update("""
                insert into custody_gas_usage(id, tenant_id, gas_account_id, operation_type, operation_id,
                    reference_no, chain, native_symbol, reserved_amount, status, pricing_source)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?)
                """, id, tenantId, gasAccountId, operationType, operationId, referenceNo, chain,
                nativeSymbol, reservedAmount, pricingSource);
    }

    /** 查询并锁定操作对应的 Gas 使用记录。 */
    public Optional<Map<String, Object>> findForUpdate(UUID tenantId, String operationType, UUID operationId) {
        return jdbc.queryForList("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where tenant_id = ? and operation_type = ? and operation_id = ?
                 for update
                """, tenantId, operationType, operationId).stream().findFirst();
    }

    /** 判断 Gas 账户是否存在尚未处理的逾期预留。 */
    public boolean existsOverdue(UUID tenantId, UUID gasAccountId) {
        return !jdbc.queryForList("""
                select id from custody_gas_usage
                 where tenant_id = ? and gas_account_id = ? and status = 'OVERDUE'
                 limit 1
                """, tenantId, gasAccountId).isEmpty();
    }

    /** 释放预留 Gas。 */
    public int release(UUID tenantId, String operationType, UUID operationId, String reason) {
        return jdbc.update("""
                update custody_gas_usage set status = 'RELEASED', error_message = ?,
                    settled_at = now(), updated_at = now()
                 where tenant_id = ? and operation_type = ? and operation_id = ? and status = 'RESERVED'
                """, reason, tenantId, operationType, operationId);
    }

    /** 结算预留 Gas。 */
    public int settle(UUID tenantId, String operationType, UUID operationId, BigDecimal actualAmount,
                      String pricingSource, String txHash) {
        return jdbc.update("""
                update custody_gas_usage set status = 'SETTLED', actual_amount = ?, pricing_source = ?,
                    tx_hash = ?, settled_at = now(), updated_at = now()
                 where tenant_id = ? and operation_type = ? and operation_id = ? and status = 'RESERVED'
                """, actualAmount, pricingSource, txHash, tenantId, operationType, operationId);
    }

    /** 将超额扣款记录标记为逾期，等待补足 Gas 余额后再次结算。 */
    public int markOverdue(UUID tenantId, UUID id, BigDecimal actualAmount,
                           String pricingSource, String txHash, String errorMessage) {
        return jdbc.update("""
                update custody_gas_usage
                   set status = 'OVERDUE', actual_amount = ?, pricing_source = ?, tx_hash = ?,
                       error_message = ?, updated_at = now(), settled_at = null
                 where tenant_id = ? and id = ? and status in ('RESERVED', 'OVERDUE')
                """, actualAmount, pricingSource, txHash, errorMessage, tenantId, id);
    }

    /** 结算预留或逾期的 Gas 记录。 */
    public int settleReservedOrOverdue(UUID tenantId, UUID id, BigDecimal actualAmount,
                                       String pricingSource, String txHash) {
        return jdbc.update("""
                update custody_gas_usage
                   set status = 'SETTLED', actual_amount = ?, pricing_source = ?, tx_hash = ?,
                       error_message = null, settled_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and status in ('RESERVED', 'OVERDUE')
                """, actualAmount, pricingSource, txHash, tenantId, id);
    }

    /** 查询超时未结算 Gas 使用记录。 */
    public List<Map<String, Object>> listOverdue(int limit) {
        return jdbc.queryForList("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where status = 'RESERVED'
                   and created_at < now() - interval '30 minutes'
                 order by created_at limit ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    /** 查询租户 Gas 使用记录。 */
    public List<Map<String, Object>> list(UUID tenantId, String chain, String status,
                                          int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage
                 where tenant_id = ?
                   and (cast(? as varchar) is null or chain = cast(? as varchar))
                   and (cast(? as varchar) is null or status = cast(? as varchar))
                 order by created_at desc, id desc limit ? offset ?
                """, tenantId, chain, chain, status, status, Math.min(Math.max(limit, 1), 500),
                Math.max(offset, 0));
    }
}
