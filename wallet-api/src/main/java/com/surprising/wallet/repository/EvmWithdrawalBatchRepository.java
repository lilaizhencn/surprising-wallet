package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.GasOperationReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.SQLException;
import java.sql.ResultSet;

/** evm_withdrawal_batch 单表仓储。 */
@Repository
public class EvmWithdrawalBatchRepository {
    private static final String COLUMNS = """
            id, tenant_id, chain, network, asset_symbol, token_contract, token_decimals, hot_wallet,
            delegate_version, batch_hash, canonical_tx_hash, status, relayer_address, authorization_included,
            authorization_nonce, operation_nonce, error_code, error_message
            """;
    public Optional<GasOperationReference> lockGasOperation(UUID tenantId, UUID id) {
        return jdbc.query("select chain, id::text as reference_no from evm_withdrawal_batch where tenant_id=? and id=? for update",
                (rs, rowNum) -> new GasOperationReference(
                        rs.getString("chain"), rs.getString("reference_no")), tenantId, id).stream().findFirst();
    }
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EVM 提现批次仓储。 */
    public EvmWithdrawalBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询批次状态。 */
    public List<String> findStatuses(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("select status from evm_withdrawal_batch where tenant_id = ? and id = ?",
                String.class, tenantId, batchId);
    }

    /** 创建提现批次。 */
    public int insert(UUID id, UUID tenantId, String chain, String network, String assetSymbol,
                      String tokenContract, int tokenDecimals, String hotWallet, String relayerAddress,
                      int delegateVersion, String batchHash, int itemCount) {
        return jdbc.update("""
                insert into evm_withdrawal_batch(id, tenant_id, chain, network, asset_symbol, token_contract,
                    token_decimals, hot_wallet, relayer_address, delegate_version, batch_hash, status, item_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCKED', ?)
                """, id, tenantId, chain, network, assetSymbol, tokenContract, tokenDecimals, hotWallet,
                relayerAddress, delegateVersion, batchHash, itemCount);
    }

    /** 保存提现批次的签名参数。 */
    public int markSigning(UUID tenantId, UUID batchId, boolean authorizationIncluded,
                           BigInteger authorizationNonce, BigInteger operationNonce, Timestamp deadline,
                           long estimatedGas, long gasLimit, BigInteger maxFeePerGas,
                           BigInteger maxPriorityFeePerGas) {
        return jdbc.update("""
                update evm_withdrawal_batch set status = 'SIGNING', authorization_included = ?,
                    authorization_nonce = ?, operation_nonce = ?, signature_deadline = ?, estimated_gas = ?,
                    gas_limit = ?, max_fee_per_gas = ?, max_priority_fee_per_gas = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'LOCKED'
                """, authorizationIncluded, authorizationNonce, operationNonce, deadline, estimatedGas, gasLimit,
                maxFeePerGas, maxPriorityFeePerGas, tenantId, batchId);
    }

    /** 标记提现批次已提交。 */
    public int markSubmitted(UUID tenantId, UUID batchId, String txHash) {
        return jdbc.update("""
                update evm_withdrawal_batch set status = 'SUBMITTED', canonical_tx_hash = ?,
                    submitted_at = coalesce(submitted_at, now()), updated_at = now()
                 where tenant_id = ? and id = ?
                   and status in ('SIGNING', 'BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING')
                   and (canonical_tx_hash is null or lower(canonical_tx_hash) = lower(?))
                """, txHash, tenantId, batchId, txHash);
    }

    /** 标记提现批次广播未知。 */
    public int markBroadcastUnknown(UUID tenantId, UUID batchId, String code, String message) {
        return jdbc.update("""
                update evm_withdrawal_batch set status = 'BROADCAST_UNKNOWN', error_code = ?,
                    error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SIGNING'
                """, code, message, tenantId, batchId);
    }

    /** 记录提现批次恢复错误。 */
    public int markRecoveryError(UUID tenantId, UUID batchId, String code, String message) {
        return jdbc.update("""
                update evm_withdrawal_batch set error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'BROADCAST_UNKNOWN'
                """, code, message, tenantId, batchId);
    }

    /** 查询提现批次单表字段。 */
    public Optional<BatchRow> find(UUID tenantId, UUID batchId) {
        return jdbc.query("select " + COLUMNS + """
                 from evm_withdrawal_batch where tenant_id = ? and id = ?
                """, EvmWithdrawalBatchRepository::mapBatch, tenantId, batchId).stream().findFirst();
    }

    /** 查询待确认提现批次。 */
    public List<BatchRow> listPending(String chain, String network, int limit) {
        return jdbc.query("select " + COLUMNS + """
                 from evm_withdrawal_batch where chain = ? and network = ? and status in ('SUBMITTED', 'CONFIRMING')
                 order by submitted_at, id limit ?
                """, EvmWithdrawalBatchRepository::mapBatch, chain, network, Math.min(Math.max(limit, 1), 200));
    }

    /** 查询广播未知提现批次。 */
    public List<UUID> listBroadcastUnknown(String chain, String network) {
        return jdbc.queryForList("""
                select id from evm_withdrawal_batch
                 where chain = ? and network = ? and status = 'BROADCAST_UNKNOWN'
                """, UUID.class, chain, network);
    }

    /** 查询没有签名尝试和链上交易的预广播失败批次。 */
    public List<BatchRow> listFailedUnbroadcast(String chain, String network, int limit) {
        return jdbc.query("select " + COLUMNS + """
                 from evm_withdrawal_batch where chain = ? and network = ? and status = 'FAILED'
                   and canonical_tx_hash is null and error_code = 'PREPARATION_FAILED'
                 order by updated_at, id limit ?
                """, EvmWithdrawalBatchRepository::mapBatch, chain, network, Math.min(Math.max(limit, 1), 200));
    }

    /** 完成提现批次。 */
    public int complete(UUID tenantId, UUID batchId, String txHash, String status, BigInteger gasUsed,
                        BigInteger effectiveGasPrice, BigInteger l2Fee, BigInteger l1Fee,
                        BigInteger operatorFee, BigInteger totalFee, BigDecimal actualFee,
                        BigInteger blockNumber, String blockHash, String errorCode, String errorMessage) {
        return jdbc.update("""
                update evm_withdrawal_batch set status = ?, actual_gas_used = ?, effective_gas_price = ?,
                    l2_fee_atomic = ?, l1_fee_atomic = ?, operator_fee_atomic = ?, total_fee_atomic = ?,
                    actual_fee = ?, confirmed_block_number = ?, confirmed_block_hash = ?, error_code = ?,
                    error_message = ?, confirmed_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and canonical_tx_hash = ?
                   and status in ('SUBMITTED', 'CONFIRMING')
                """, status, gasUsed, effectiveGasPrice, l2Fee, l1Fee, operatorFee, totalFee, actualFee,
                blockNumber, blockHash, errorCode, errorMessage, tenantId, batchId, txHash);
    }

    /** 将未广播批次标记失败。 */
    public int markFailedIfLocked(UUID tenantId, UUID batchId, String code, String message) {
        return jdbc.update("""
                update evm_withdrawal_batch set status = 'FAILED', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'LOCKED'
                """, code, message, tenantId, batchId);
    }

    /** 将没有广播交易的锁定批次记录为最终失败，重复执行保持幂等。 */
    public int markFailedIfUnbroadcast(UUID tenantId, UUID batchId, String code, String message) {
        return jdbc.update("""
                update evm_withdrawal_batch set status = 'FAILED', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and canonical_tx_hash is null
                   and status in ('LOCKED', 'FAILED')
                """, code, message, tenantId, batchId);
    }
public record BatchRow(UUID id,
        UUID tenantId,
        String chain,
        String network,
        String assetSymbol,
        String tokenContract,
        int tokenDecimals,
        String hotWallet,
        int delegateVersion,
        String batchHash,
        String canonicalTxHash,
        String status,
        String relayerAddress,
        boolean authorizationIncluded,
        BigInteger authorizationNonce,
        BigInteger operationNonce,
        String errorCode,
        String errorMessage) { }

private static BatchRow mapBatch(ResultSet rs, int rowNum) throws SQLException {
    return new BatchRow(rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("chain"),
            rs.getString("network"),
            rs.getString("asset_symbol"),
            rs.getString("token_contract"),
            rs.getInt("token_decimals"),
            rs.getString("hot_wallet"),
            rs.getInt("delegate_version"),
            rs.getString("batch_hash"),
            rs.getString("canonical_tx_hash"),
            rs.getString("status"),
            rs.getString("relayer_address"),
            rs.getBoolean("authorization_included"),
            numberOrNull(rs.getBigDecimal("authorization_nonce")),
            numberOrNull(rs.getBigDecimal("operation_nonce")),
            rs.getString("error_code"),
            rs.getString("error_message"));
}

private static BigInteger numberOrNull(BigDecimal value) {
    return value == null ? null : value.toBigIntegerExact();
}

}
