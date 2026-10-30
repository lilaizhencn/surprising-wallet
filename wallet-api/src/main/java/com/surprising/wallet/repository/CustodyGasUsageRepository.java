package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.CustodyGasUsageEntry;
import com.surprising.wallet.model.custody.GasUsageRecord;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** custody_gas_usage 单表仓储。 */
@Repository
public class CustodyGasUsageRepository {
    public List<CustodyGasUsageEntry> listForAccount(UUID tenantId, UUID gasAccountId, int limit, int offset) {
        return jdbc.query("""
                select id, operation_type, operation_id, reference_no, chain, native_symbol,
                       reserved_amount, actual_amount, status, pricing_source, tx_hash, error_message,
                       created_at, updated_at, settled_at
                  from custody_gas_usage where tenant_id = ? and gas_account_id = ?
                 order by created_at desc, id desc limit ? offset ?
                """, (rs, rowNum) -> new CustodyGasUsageEntry(rs.getObject("id", UUID.class),
                rs.getString("operation_type"), rs.getObject("operation_id", UUID.class), rs.getString("reference_no"),
                rs.getString("chain"), rs.getString("native_symbol"), rs.getBigDecimal("reserved_amount"),
                rs.getBigDecimal("actual_amount"), rs.getString("status"), rs.getString("pricing_source"),
                rs.getString("tx_hash"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("settled_at") == null ? null : rs.getTimestamp("settled_at").toInstant()),
                tenantId, gasAccountId, Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Gas 使用记录仓储。 */
    public CustodyGasUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GasUsageRecord> withdrawalFees(UUID tenantId,List<UUID> withdrawalIds) {
        if(withdrawalIds.isEmpty()) return List.of();
        return jdbc.query("select * from custody_gas_usage where tenant_id=? and operation_type='WITHDRAWAL' and operation_id=any(?)",
                CustodyGasUsageRepository::mapUsage,tenantId,new org.springframework.jdbc.support.SqlArrayValue("uuid",withdrawalIds.toArray()));
    }

    /** 查询操作对应的 Gas 使用记录。 */
    public Optional<GasUsageRecord> find(UUID tenantId, String operationType, UUID operationId) {
        return jdbc.query("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where tenant_id = ? and operation_type = ? and operation_id = ?
                """, CustodyGasUsageRepository::mapUsage, tenantId, operationType, operationId).stream().findFirst();
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
    public Optional<GasUsageRecord> findForUpdate(UUID tenantId, String operationType, UUID operationId) {
        return jdbc.query("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where tenant_id = ? and operation_type = ? and operation_id = ?
                 for update
                """, CustodyGasUsageRepository::mapUsage, tenantId, operationType, operationId).stream().findFirst();
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

    /** 按主键分页查询待对账记录，未确认预留不能挡住后面的已确认费用。 */
    public List<GasUsageRecord> listReconciliationCandidates(UUID afterId, int limit) {
        return jdbc.query("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage where (status = 'OVERDUE'
                    or (status = 'RESERVED' and created_at < now() - interval '30 minutes'))
                    and (cast(? as uuid) is null or id > cast(? as uuid))
                 order by id limit ?
                """, CustodyGasUsageRepository::mapUsage, afterId, afterId, Math.min(Math.max(limit, 1), 500));
    }

    /** 查询租户 Gas 使用记录。 */
    public List<GasUsageRecord> list(UUID tenantId, String chain, String status,
                                          int limit, int offset) {
        return jdbc.query("""
                select id, tenant_id, gas_account_id, operation_type, operation_id, reference_no,
                       chain, native_symbol, reserved_amount, actual_amount, status, pricing_source,
                       tx_hash, error_message, created_at, updated_at, settled_at
                  from custody_gas_usage
                 where tenant_id = ?
                   and (cast(? as varchar) is null or chain = cast(? as varchar))
                   and (cast(? as varchar) is null or status = cast(? as varchar))
                 order by created_at desc, id desc limit ? offset ?
                """, CustodyGasUsageRepository::mapUsage, tenantId, chain, chain, status, status, Math.min(Math.max(limit, 1), 500),
                Math.max(offset, 0));
    }
    private static GasUsageRecord mapUsage(ResultSet rs, int rowNum) throws SQLException {
        return new GasUsageRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("gas_account_id", UUID.class), rs.getString("operation_type"),
                rs.getObject("operation_id", UUID.class), rs.getString("reference_no"), rs.getString("chain"),
                rs.getString("native_symbol"), rs.getBigDecimal("reserved_amount"), rs.getBigDecimal("actual_amount"),
                rs.getString("status"), rs.getString("pricing_source"), rs.getString("tx_hash"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("settled_at") == null ? null : rs.getTimestamp("settled_at").toInstant());
    }

}
