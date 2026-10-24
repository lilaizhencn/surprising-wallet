package com.surprising.wallet.repository;

import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** evm_withdrawal_batch_item 单表仓储。 */
@Repository
public class EvmWithdrawalBatchItemRepository {
    private static final String COLUMNS = """
            tenant_id, batch_id, item_index, withdrawal_order_id, custody_withdrawal_id,
            withdrawal_id_hash, token_contract, recipient, requested_amount_atomic, status
            """;
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造提现批次项仓储。 */
    public EvmWithdrawalBatchItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询订单关联的已提交提现批次项。 */
    public List<UUID> listSubmittedByWithdrawal(UUID tenantId, long withdrawalOrderId) {
        return jdbc.queryForList("""
                select batch_id from evm_withdrawal_batch_item
                 where tenant_id = ? and withdrawal_order_id = ? and status = 'SUBMITTED'
                """, UUID.class, tenantId, withdrawalOrderId);
    }

    /** 创建提现批次项。 */
    public int insert(UUID id, UUID tenantId, UUID batchId, int itemIndex, long withdrawalOrderId,
                      UUID custodyWithdrawalId, String withdrawalIdHash, String recipient,
                      String tokenContract, BigInteger amountAtomic) {
        return jdbc.update("""
                insert into evm_withdrawal_batch_item(
                    id, tenant_id, batch_id, item_index, withdrawal_order_id, custody_withdrawal_id,
                    withdrawal_id_hash, recipient, token_contract, requested_amount_atomic, call_gas_limit, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 120000, 'CREATED')
                """, id, tenantId, batchId, itemIndex, withdrawalOrderId, custodyWithdrawalId,
                withdrawalIdHash, recipient, tokenContract, amountAtomic);
    }

    /** 将全部提现批次项标记为已签名。 */
    public int markSigned(UUID tenantId, UUID batchId, int expectedCount) {
        return jdbc.update("""
                update evm_withdrawal_batch_item set status = 'SIGNED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, tenantId, batchId);
    }

    /** 将提现批次项标记为已提交。 */
    public int markSubmitted(UUID tenantId, UUID batchId) {
        return jdbc.update("""
                update evm_withdrawal_batch_item set status = 'SUBMITTED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'SIGNED'
                """, tenantId, batchId);
    }

    /** 查询批次项单表字段。 */
    public List<Item> listByBatch(UUID tenantId, UUID batchId) {
        return jdbc.query("select " + COLUMNS + """
                 from evm_withdrawal_batch_item
                 where tenant_id = ? and batch_id = ? order by item_index
                """, EvmWithdrawalBatchItemRepository::mapItem, tenantId, batchId);
    }

    /** 判断批次是否仍有需要收敛的预广播失败项。 */
    public boolean hasPreBroadcastRecoverableItems(UUID tenantId, UUID batchId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from evm_withdrawal_batch_item
                 where tenant_id = ? and batch_id = ?
                   and status in ('CREATED', 'SIGNED', 'RETRYABLE')
                """, Integer.class, tenantId, batchId);
        return count != null && count > 0;
    }

    /** 更新提现批次项执行结果。 */
    public int markResult(UUID tenantId, UUID batchId, int itemIndex,
                          Evm7702PayoutReceiptParser.ItemResult result, String status) {
        return jdbc.update("""
                update evm_withdrawal_batch_item set actual_received_atomic = ?, status = ?, log_index = ?,
                    error_hash = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'SUBMITTED'
                """, result.actualReceived(), status, (long) result.logIndex(),
                result.success() ? null : result.errorHash(), tenantId, batchId, itemIndex);
    }

    /** 统计订单历史失败项数量。 */
    public int countFailedAttempts(long withdrawalOrderId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from evm_withdrawal_batch_item
                 where withdrawal_order_id = ? and status in ('RETRYABLE', 'FAILED')
                """, Integer.class, withdrawalOrderId);
        return count == null ? 0 : count;
    }

    /** 将提现批次项标记为回滚结果。 */
    public int markReverted(UUID tenantId, UUID batchId, int itemIndex, String status, String errorHash) {
        return jdbc.update("""
                update evm_withdrawal_batch_item set actual_received_atomic = 0, status = ?,
                    error_code = 'OUTER_REVERTED', error_hash = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'SUBMITTED'
                """, status, errorHash, tenantId, batchId, itemIndex);
    }

    /** 查询未广播批次项。 */
    public List<Item> listCreated(UUID tenantId, UUID batchId) {
        return jdbc.query("select " + COLUMNS + """
                 from evm_withdrawal_batch_item
                 where tenant_id = ? and batch_id = ? and status = 'CREATED' order by item_index
                """, EvmWithdrawalBatchItemRepository::mapItem, tenantId, batchId);
    }

    /** 将未广播批次项标记为可重试。 */
    public int markRetryable(UUID tenantId, UUID batchId, int itemIndex, String code) {
        return jdbc.update("""
                update evm_withdrawal_batch_item set status = 'RETRYABLE', error_code = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'CREATED'
                """, code, tenantId, batchId, itemIndex);
    }

    /** 将没有广播交易的批次项记录为最终失败，重复执行保持幂等。 */
    public int markPreBroadcastFailed(UUID tenantId, UUID batchId, int itemIndex, String code) {
        return jdbc.update("""
                update evm_withdrawal_batch_item
                   set status = 'FAILED', error_code = ?, actual_received_atomic = 0, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ?
                   and status in ('CREATED', 'SIGNED', 'RETRYABLE')
                """, code, tenantId, batchId, itemIndex);
    }
public record Item(UUID tenantId, UUID batchId, int itemIndex, long withdrawalOrderId,
                   UUID custodyWithdrawalId, String withdrawalIdHash, String tokenContract,
                   String recipient, BigInteger requestedAmountAtomic, String status) { }

private static Item mapItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new Item(rs.getObject("tenant_id", UUID.class), rs.getObject("batch_id", UUID.class),
            rs.getInt("item_index"), rs.getLong("withdrawal_order_id"), rs.getObject("custody_withdrawal_id", UUID.class),
            rs.getString("withdrawal_id_hash"), rs.getString("token_contract"), rs.getString("recipient"),
            rs.getBigDecimal("requested_amount_atomic").toBigIntegerExact(), rs.getString("status"));
}

}
