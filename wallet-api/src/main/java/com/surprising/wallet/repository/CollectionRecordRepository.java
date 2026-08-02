package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.ChainCollectionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** collection_record 单表仓储。 */
@Repository
public class CollectionRecordRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造归集记录仓储。 */
    public CollectionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在归集记录。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from collection_record where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在归集记录。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from collection_record
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

    /** 判断交易是否为租户内部归集转账。 */
    public boolean existsInternalTransfer(UUID tenantId, String chain, String txHash, String toAddress) {
        return !jdbc.queryForList("""
                select id from collection_record
                 where tenant_id = ? and chain = ? and lower(tx_hash) = lower(?)
                   and lower(to_address) = lower(?) limit 1
                """, tenantId, chain, txHash, toAddress).isEmpty();
    }

    /** 创建租户归集记录。 */
    public int create(UUID tenantId, UUID custodyAddressId, String collectionNo, String chain, String assetSymbol,
                      String fromAddress, String toAddress, BigDecimal amount, BigDecimal fee, String rawPayload) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into collection_record(collection_no, chain, asset_symbol, from_address, to_address,
                    amount, fee, status, raw_payload, tenant_id, custody_address_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                on conflict (chain, collection_no) do nothing
                """, collectionNo, chain, assetSymbol, fromAddress, toAddress, amount, fee, rawPayload,
                tenantId, custodyAddressId, now, now);
    }

    /** 查询待签名归集记录。 */
    public List<ChainCollectionRecord> listForSigning(String chain, int limit) {
        return list("tenant_id is not null and custody_address_id is not null and chain = ? "
                + "and status in ('CREATED', 'RETRYING')", new Object[]{chain, limit});
    }

    /** 按状态查询归集记录。 */
    public List<ChainCollectionRecord> listByStatus(String chain, String status, int limit) {
        return list("tenant_id is not null and custody_address_id is not null and chain = ? and status = ?",
                new Object[]{chain, status, limit});
    }

    /** 更新归集记录状态。 */
    public int updateStatus(UUID tenantId, String chain, String collectionNo, String status,
                            String txHash, String errorMessage, String rawPayload) {
        return jdbc.update("""
                update collection_record set status = ?, tx_hash = coalesce(?, tx_hash), error_message = ?,
                    raw_payload = coalesce(?, raw_payload), updated_at = ?
                 where tenant_id = ? and chain = ? and collection_no = ?
                """, status, txHash, errorMessage, rawPayload, Timestamp.from(Instant.now()),
                tenantId, chain, collectionNo);
    }

    /** 领取归集签名状态。 */
    public int claimSigning(UUID tenantId, String chain, String collectionNo, String rawPayload) {
        return jdbc.update("""
                update collection_record set status = 'SIGNING', error_message = null,
                    raw_payload = coalesce(?, raw_payload), updated_at = ?
                 where tenant_id = ? and chain = ? and collection_no = ?
                   and status in ('CREATED', 'RETRYING')
                """, rawPayload, Timestamp.from(Instant.now()), tenantId, chain, collectionNo);
    }

    /** 按主键将归集记录标记为已发送。 */
    public int markSent(UUID tenantId, long id, String txHash) {
        return jdbc.update("""
                update collection_record set status = 'SENT', tx_hash = ?, error_message = null, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('SIGNING', 'SENT')
                """, txHash, tenantId, id);
    }

    /** 按主键更新归集执行结果。 */
    public int updateExecution(UUID tenantId, long id, String status, String txHash, String errorMessage) {
        return jdbc.update("""
                update collection_record set status = ?, tx_hash = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, status, txHash, errorMessage, tenantId, id);
    }

    /** 标记归集已确认。 */
    public int markConfirmed(UUID tenantId, String chain, String collectionNo, String txHash) {
        return jdbc.update("""
                update collection_record set status = 'CONFIRMED', tx_hash = ?, error_message = null,
                    updated_at = ?
                 where tenant_id = ? and chain = ? and collection_no = ? and status <> 'CONFIRMED'
                """, txHash, Timestamp.from(Instant.now()), tenantId, chain, collectionNo);
    }

    /** 查询归集状态。 */
    public Optional<String> findStatus(UUID tenantId, String chain, String collectionNo) {
        return jdbc.queryForList("""
                select status from collection_record where tenant_id = ? and chain = ? and collection_no = ?
                """, String.class, tenantId, chain, collectionNo).stream().findFirst();
    }

    /** 查询归集交易哈希。 */
    public Optional<String> findTxHash(UUID tenantId, String chain, String collectionNo) {
        return jdbc.queryForList("""
                select tx_hash from collection_record
                 where tenant_id = ? and chain = ? and collection_no = ? and tx_hash is not null
                """, String.class, tenantId, chain, collectionNo).stream().findFirst();
    }

    /** 查询指定链的归集记录字段，供服务层按租户和地址组合统计。 */
    public List<java.util.Map<String, Object>> listForCollectionBalance(String chain) {
        return jdbc.queryForList("""
                select tenant_id, asset_symbol, lower(from_address) as from_address, amount, status
                  from collection_record where chain = ? and tenant_id is not null
                """, chain);
    }

    /** 查询可参与 EVM 归集批次的记录，仅锁定归集记录单表行。 */
    public List<Map<String, Object>> listClaimable(String chain, int limit) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, collection_no, chain, asset_symbol,
                       from_address, to_address, amount, fee, status, tx_hash
                  from collection_record
                 where chain = ? and tenant_id is not null and custody_address_id is not null
                   and status in ('CREATED', 'RETRYING')
                 order by id
                 limit ?
                 for update skip locked
                """, chain, Math.min(Math.max(limit, 1), 500));
    }

    /** 按主键查询归集记录单表字段。 */
    public Optional<Map<String, Object>> findById(UUID tenantId, long id) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, collection_no, chain, asset_symbol,
                       from_address, to_address, amount, fee, status, tx_hash
                  from collection_record where tenant_id = ? and id = ?
                """, tenantId, id).stream().findFirst();
    }

    /** 按主键领取归集记录。 */
    public int claimSigning(UUID tenantId, long id) {
        return jdbc.update("""
                update collection_record set status = 'SIGNING', error_message = null, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('CREATED', 'RETRYING')
                """, tenantId, id);
    }

    /** 查询归集记录。 */
    private List<ChainCollectionRecord> list(String predicate, Object[] args) {
        String sql = """
                select id, tenant_id, custody_address_id, collection_no, chain, asset_symbol, from_address,
                       to_address, amount, fee, tx_hash, status, error_message, raw_payload, created_at, updated_at
                  from collection_record
                 where
                """ + predicate + " order by id limit ?";
        return jdbc.query(sql, (rs, rowNum) -> map(rs), args);
    }

    /** 映射归集记录。 */
    private static ChainCollectionRecord map(ResultSet rs) throws SQLException {
        return ChainCollectionRecord.builder().id(rs.getLong("id"))
                .tenantId(rs.getObject("tenant_id", UUID.class))
                .custodyAddressId(rs.getObject("custody_address_id", UUID.class))
                .collectionNo(rs.getString("collection_no")).chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol")).fromAddress(rs.getString("from_address"))
                .toAddress(rs.getString("to_address")).amount(rs.getBigDecimal("amount"))
                .fee(rs.getBigDecimal("fee")).txHash(rs.getString("tx_hash"))
                .status(rs.getString("status")).errorMessage(rs.getString("error_message"))
                .rawPayload(rs.getString("raw_payload"))
                .createdAt(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant())
                .build();
    }

}
