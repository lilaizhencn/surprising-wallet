package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** evm_collection_batch_attempt 单表仓储。 */
@Repository
public class EvmCollectionBatchAttemptRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造归集批次尝试仓储。 */
    public EvmCollectionBatchAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建签名尝试记录。 */
    public int insert(UUID id, UUID tenantId, UUID batchId, BigInteger relayerNonce, String txHash,
                      BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas, long gasLimit,
                      String calldataHash, String signedCiphertext, String encryptionKeyVersion) {
        return jdbc.update("""
                insert into evm_collection_batch_attempt(
                    id, tenant_id, batch_id, attempt_no, relayer_nonce, tx_hash, max_fee_per_gas,
                    max_priority_fee_per_gas, gas_limit, calldata_hash, signed_tx_ciphertext,
                    encryption_key_version, status)
                values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED')
                """, id, tenantId, batchId, relayerNonce, txHash, maxFeePerGas, maxPriorityFeePerGas,
                gasLimit, calldataHash, signedCiphertext, encryptionKeyVersion);
    }

    /** 将尝试标记为已提交。 */
    public int markSubmitted(UUID tenantId, UUID batchId, String txHash) {
        return jdbc.update("""
                update evm_collection_batch_attempt set status = 'SUBMITTED', submitted_at = coalesce(submitted_at, now())
                 where tenant_id = ? and batch_id = ? and tx_hash = ?
                   and status in ('CREATED', 'UNKNOWN', 'SUBMITTED', 'PENDING')
                """, tenantId, batchId, txHash);
    }

    /** 将尝试标记为未知。 */
    public int markUnknown(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        return jdbc.update("""
                update evm_collection_batch_attempt set status = 'UNKNOWN', error_code = ?, error_message = ?
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, errorCode, errorMessage, tenantId, batchId);
    }

    /** 将尝试标记为确认。 */
    public int markConfirmed(UUID tenantId, UUID batchId, String txHash) {
        return jdbc.update("""
                update evm_collection_batch_attempt set status = 'CONFIRMED', observed_at = now()
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status in ('SUBMITTED', 'PENDING')
                """, tenantId, batchId, txHash);
    }

    /** 查询未广播尝试数量。 */
    public int countByBatch(UUID tenantId, UUID batchId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from evm_collection_batch_attempt where tenant_id = ? and batch_id = ?
                """, Integer.class, tenantId, batchId);
        return count == null ? 0 : count;
    }

    /** 查询需要重播的未知尝试。 */
    public List<Map<String, Object>> listUnknown(int limit) {
        return jdbc.queryForList("""
                select tenant_id, batch_id, tx_hash, signed_tx_ciphertext, rebroadcast_count,
                       last_rebroadcast_at, created_at
                  from evm_collection_batch_attempt
                 where status = 'UNKNOWN'
                   and (last_rebroadcast_at is null or last_rebroadcast_at < now() - interval '30 seconds')
                 order by coalesce(last_rebroadcast_at, created_at), batch_id limit ?
                """, Math.min(Math.max(limit, 1), 100));
    }

    /** 记录未知尝试重播。 */
    public int recordRecovery(UUID tenantId, UUID batchId, String txHash) {
        return jdbc.update("""
                update evm_collection_batch_attempt set rebroadcast_count = rebroadcast_count + 1,
                    last_rebroadcast_at = now(), error_code = 'REBROADCASTING',
                    error_message = 'resubmitting the persisted raw transaction'
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status = 'UNKNOWN'
                """, tenantId, batchId, txHash);
    }

    /** 记录未知尝试错误。 */
    public int markRecoveryError(UUID tenantId, UUID batchId, String txHash,
                                 String errorCode, String errorMessage) {
        return jdbc.update("""
                update evm_collection_batch_attempt set error_code = ?, error_message = ?
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status = 'UNKNOWN'
                """, errorCode, errorMessage, tenantId, batchId, txHash);
    }
}
