package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** evm_withdrawal_batch 单表仓储。 */
@Repository
public class EvmWithdrawalBatchRepository {
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
    public List<Map<String, Object>> find(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("""
                select tenant_id, id, chain, network, asset_symbol, token_contract, token_decimals,
                       hot_wallet, delegate_version, canonical_tx_hash, status, operation_nonce,
                       authorization_included, authorization_nonce, relayer_address, batch_hash
                  from evm_withdrawal_batch where tenant_id = ? and id = ?
                """, tenantId, batchId);
    }

    /** 查询待确认提现批次。 */
    public List<Map<String, Object>> listPending(String chain, String network, int limit) {
        return jdbc.queryForList("""
                select tenant_id, id, chain, network, canonical_tx_hash, status, hot_wallet,
                       delegate_version
                  from evm_withdrawal_batch
                 where chain = ? and network = ? and status in ('SUBMITTED', 'CONFIRMING')
                 order by submitted_at, id limit ?
                """, chain, network, Math.min(Math.max(limit, 1), 200));
    }

    /** 查询广播未知提现批次。 */
    public List<Map<String, Object>> listBroadcastUnknown(String chain, String network) {
        return jdbc.queryForList("""
                select tenant_id, id, chain, network, status
                  from evm_withdrawal_batch
                 where chain = ? and network = ? and status = 'BROADCAST_UNKNOWN'
                """, chain, network);
    }

    /** 查询没有签名尝试和链上交易的预广播失败批次。 */
    public List<Map<String, Object>> listFailedUnbroadcast(String chain, String network, int limit) {
        return jdbc.queryForList("""
                select tenant_id, id, chain, network, error_code, error_message
                  from evm_withdrawal_batch
                 where chain = ? and network = ? and status = 'FAILED'
                   and canonical_tx_hash is null and error_code = 'PREPARATION_FAILED'
                 order by updated_at, id limit ?
                """, chain, network, Math.min(Math.max(limit, 1), 200));
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
}
