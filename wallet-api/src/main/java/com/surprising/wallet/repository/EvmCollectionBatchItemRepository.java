package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.UUID;

/** evm_collection_batch_item 单表仓储。 */
@Repository
public class EvmCollectionBatchItemRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造批量归集项仓储。 */
    public EvmCollectionBatchItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询指定归集记录关联的已提交批次项。 */
    public List<Map<String, Object>> listSubmittedByCollection(UUID tenantId, long collectionRecordId) {
        return jdbc.queryForList("""
                select batch_id, status from evm_collection_batch_item
                 where tenant_id = ? and collection_record_id = ? and status = 'SUBMITTED'
                """, tenantId, collectionRecordId);
    }

    /** 创建归集批次项。 */
    public int insert(UUID id, UUID tenantId, UUID batchId, int itemIndex, long collectionRecordId,
                      UUID custodyAddressId, String authorityAddress, String tokenContract,
                      String recipient, BigInteger amountAtomic, Timestamp signatureDeadline) {
        return jdbc.update("""
                insert into evm_collection_batch_item(
                    id, tenant_id, batch_id, item_index, collection_record_id, custody_address_id,
                    authority_address, token_contract, recipient, requested_amount_atomic, operation_nonce,
                    signature_deadline, call_gas_limit, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 180000, 'CREATED')
                """, id, tenantId, batchId, itemIndex, collectionRecordId, custodyAddressId,
                authorityAddress, tokenContract, recipient, amountAtomic, signatureDeadline);
    }

    /** 保存归集批次项签名准备数据。 */
    public int markSigned(UUID tenantId, UUID batchId, int itemIndex, String authorityAddress,
                          boolean authorizationIncluded, BigInteger authorizationNonce,
                          BigInteger operationNonce, Timestamp deadline, long callGasLimit) {
        return jdbc.update("""
                update evm_collection_batch_item set authorization_included = ?, authorization_nonce = ?,
                    operation_nonce = ?, signature_deadline = ?, call_gas_limit = ?, status = 'SIGNED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ?
                   and lower(authority_address) = lower(?) and status = 'CREATED'
                """, authorizationIncluded, authorizationNonce, operationNonce, deadline, callGasLimit,
                tenantId, batchId, itemIndex, authorityAddress);
    }

    /** 将批次项标记为已提交。 */
    public int markSubmitted(UUID tenantId, UUID batchId) {
        return jdbc.update("""
                update evm_collection_batch_item set status = 'SUBMITTED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'SIGNED'
                """, tenantId, batchId);
    }

    /** 查询批次项身份。 */
    public List<Map<String, Object>> listIdentities(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("""
                select item_index, authority_address, token_contract, recipient, requested_amount_atomic
                  from evm_collection_batch_item where tenant_id = ? and batch_id = ? order by item_index
                """, tenantId, batchId);
    }

    /** 查询批次关联的归集记录主键。 */
    public List<Long> listCollectionRecordIds(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("""
                select collection_record_id from evm_collection_batch_item
                 where tenant_id = ? and batch_id = ? order by item_index
                """, Long.class, tenantId, batchId);
    }

    /** 更新归集批次项执行结果。 */
    public int complete(UUID tenantId, UUID batchId, int itemIndex, BigInteger actualReceived,
                        String status, Long logIndex, String errorHash) {
        return jdbc.update("""
                update evm_collection_batch_item set actual_received_atomic = ?, status = ?, log_index = ?,
                    error_hash = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'SUBMITTED'
                """, actualReceived, status, logIndex, errorHash, tenantId, batchId, itemIndex);
    }

    /** 查询归集批次项完成所需的单表字段。 */
    public List<Map<String, Object>> findForCompletion(UUID tenantId, UUID batchId, int itemIndex) {
        return jdbc.queryForList("""
                select collection_record_id, custody_address_id, authorization_included, operation_nonce
                  from evm_collection_batch_item
                 where tenant_id = ? and batch_id = ? and item_index = ?
                """, tenantId, batchId, itemIndex);
    }

    /** 查询批次项数量。 */
    public int countByBatch(UUID tenantId, UUID batchId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from evm_collection_batch_item where tenant_id = ? and batch_id = ?
                """, Integer.class, tenantId, batchId);
        return count == null ? 0 : count;
    }

    /** 查询指定归集记录已经创建过的批次项次数，用于限制连续失败重试次数。 */
    public int countAttemptHistory(UUID tenantId, long collectionRecordId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from evm_collection_batch_item
                 where tenant_id = ? and collection_record_id = ?
                """, Integer.class, tenantId, collectionRecordId);
        return count == null ? 0 : count;
    }

    /** 标记未广播批次项可重试或失败。 */
    public int markReleased(UUID tenantId, UUID batchId, String status, String errorCode) {
        return jdbc.update("""
                update evm_collection_batch_item set status = ?, error_code = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, status, errorCode, tenantId, batchId);
    }

    /** 查询未广播批次项的归集记录主键。 */
    public List<Map<String, Object>> listForRelease(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("""
                select item_index, collection_record_id, status
                  from evm_collection_batch_item
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                 order by item_index
                """, tenantId, batchId);
    }

    /** 按位置标记未广播批次项状态。 */
    public int markReleased(UUID tenantId, UUID batchId, int itemIndex, String status, String errorCode) {
        return jdbc.update("""
                update evm_collection_batch_item set status = ?, error_code = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'CREATED'
                """, status, errorCode, tenantId, batchId, itemIndex);
    }
}
