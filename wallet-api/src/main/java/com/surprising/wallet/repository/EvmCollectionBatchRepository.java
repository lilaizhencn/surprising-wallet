package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/** evm_collection_batch 单表仓储。 */
@Repository
public class EvmCollectionBatchRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造批量归集仓储。 */
    public EvmCollectionBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询批次状态。 */
    public List<String> findStatuses(UUID tenantId, long batchId) {
        return jdbc.queryForList("""
                select status from evm_collection_batch where tenant_id = ? and id = ?
                """, String.class, tenantId, batchId);
    }

    /** 判断指定链网络是否存在待确认批次。 */
    public boolean existsPending(String chain, String network) {
        return !jdbc.queryForList("""
                select id from evm_collection_batch
                 where chain = ? and network = ?
                   and status in ('BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING') limit 1
                """, chain, network).isEmpty();
    }

    /** 创建归集批次。 */
    public int insert(UUID id, UUID tenantId, String chain, String network, String assetSymbol,
                      String tokenContract, int tokenDecimals, String hotWallet, String relayerAddress,
                      int delegateVersion, String batchHash, int itemCount) {
        return jdbc.update("""
                insert into evm_collection_batch(
                    id, tenant_id, chain, network, asset_symbol, token_contract, token_decimals, hot_wallet,
                    relayer_address, delegate_version, batch_hash, status, item_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCKED', ?)
                """, id, tenantId, chain, network, assetSymbol, tokenContract, tokenDecimals, hotWallet,
                relayerAddress, delegateVersion, batchHash, itemCount);
    }

    /** 将归集批次置为签名中。 */
    public int markSigning(UUID tenantId, UUID batchId, long estimatedGas, long gasLimit,
                           BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas) {
        return jdbc.update("""
                update evm_collection_batch set status = 'SIGNING', estimated_gas = ?, gas_limit = ?,
                    max_fee_per_gas = ?, max_priority_fee_per_gas = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'LOCKED'
                """, estimatedGas, gasLimit, maxFeePerGas, maxPriorityFeePerGas, tenantId, batchId);
    }

    /** 标记归集批次已提交。 */
    public int markSubmitted(UUID tenantId, UUID batchId, String txHash) {
        return jdbc.update("""
                update evm_collection_batch set status = 'SUBMITTED', canonical_tx_hash = ?,
                    submitted_at = coalesce(submitted_at, now()), updated_at = now()
                 where tenant_id = ? and id = ?
                   and status in ('SIGNING', 'BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING')
                   and (canonical_tx_hash is null or lower(canonical_tx_hash) = lower(?))
                """, txHash, tenantId, batchId, txHash);
    }

    /** 标记归集批次广播未知。 */
    public int markBroadcastUnknown(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        return jdbc.update("""
                update evm_collection_batch set status = 'BROADCAST_UNKNOWN', error_code = ?,
                    error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SIGNING'
                """, errorCode, errorMessage, tenantId, batchId);
    }

    /** 记录广播未知批次的恢复错误。 */
    public int markRecoveryError(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        return jdbc.update("""
                update evm_collection_batch set error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'BROADCAST_UNKNOWN'
                """, errorCode, errorMessage, tenantId, batchId);
    }

    /** 查询归集批次。 */
    public List<Map<String, Object>> find(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("""
                select tenant_id, id, chain, network, delegate_version, canonical_tx_hash, status,
                       asset_symbol, token_contract, token_decimals, hot_wallet, batch_hash
                  from evm_collection_batch where tenant_id = ? and id = ?
                """, tenantId, batchId);
    }

    /** 查询待确认归集批次。 */
    public List<Map<String, Object>> listPending(String chain, String network, int limit) {
        return jdbc.queryForList("""
                select tenant_id, id, canonical_tx_hash, status, chain, network, delegate_version
                  from evm_collection_batch where chain = ? and network = ?
                   and status in ('SUBMITTED', 'CONFIRMING') order by submitted_at, id limit ?
                """, chain, network, Math.min(Math.max(limit, 1), 200));
    }

    /** 查询广播未知批次。 */
    public List<Map<String, Object>> listBroadcastUnknown(String chain, String network) {
        return jdbc.queryForList("""
                select tenant_id, id, chain, network, status
                  from evm_collection_batch
                 where chain = ? and network = ? and status = 'BROADCAST_UNKNOWN'
                """, chain, network);
    }

    /** 更新归集批次完成结果。 */
    public int complete(UUID tenantId, UUID batchId, String txHash, String status,
                        BigInteger gasUsed, BigInteger effectiveGasPrice, BigInteger l2Fee,
                        BigInteger l1Fee, BigInteger operatorFee, BigInteger totalFee,
                        BigDecimal actualFee, BigInteger blockNumber, String blockHash) {
        return jdbc.update("""
                update evm_collection_batch set status = ?, actual_gas_used = ?, effective_gas_price = ?,
                    l2_fee_atomic = ?, l1_fee_atomic = ?, operator_fee_atomic = ?, total_fee_atomic = ?,
                    actual_fee = ?, confirmed_block_number = ?, confirmed_block_hash = ?,
                    confirmed_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and canonical_tx_hash = ?
                   and status in ('SUBMITTED', 'CONFIRMING')
                """, status, gasUsed, effectiveGasPrice, l2Fee, l1Fee, operatorFee, totalFee, actualFee,
                blockNumber, blockHash, tenantId, batchId, txHash);
    }

    /** 将未广播批次标记失败。 */
    public int markFailedIfUnbroadcast(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        return jdbc.update("""
                update evm_collection_batch set status = 'FAILED', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('LOCKED', 'SIGNING')
                """, errorCode, errorMessage, tenantId, batchId);
    }
}
