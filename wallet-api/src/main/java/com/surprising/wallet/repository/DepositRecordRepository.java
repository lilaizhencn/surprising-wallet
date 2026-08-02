package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** deposit_record 单表仓储。 */
@Repository
public class DepositRecordRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造充值记录仓储。 */
    public DepositRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在充值记录。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from deposit_record where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在充值记录。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from deposit_record
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

    /** 判断交易日志是否已经被充值记录以 canonical 状态处理。 */
    public boolean existsCanonical(String chain, String txHash, long logIndex) {
        return !jdbc.queryForList("""
                select id from deposit_record
                 where chain = ? and lower(tx_hash) = lower(?) and log_index = ?
                   and canonical_status = 'CANONICAL'
                 limit 1
                """, chain, txHash, logIndex).isEmpty();
    }

    /** 幂等写入充值记录并恢复被重组的 canonical 记录。 */
    public int upsert(UUID tenantId, String chain, String assetSymbol, String txHash, long logIndex,
                      String fromAddress, String toAddress, String contractAddress, BigDecimal amount,
                      long blockHeight, String blockHash, int confirmations, String status, String accountId,
                      String rawPayload) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into deposit_record(tenant_id, chain, asset_symbol, tx_hash, log_index, from_address,
                    to_address, contract_address, amount, block_height, block_hash, confirmations, status,
                    credited, credit_generation, canonical_status, account_id, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, 0, 'CANONICAL', ?, ?, ?, ?)
                on conflict (chain, tx_hash, log_index) do update set
                    block_height = case when deposit_record.canonical_status = 'REORGED' then excluded.block_height
                                        else deposit_record.block_height end,
                    block_hash = case when deposit_record.canonical_status = 'REORGED' then excluded.block_hash
                                      else deposit_record.block_hash end,
                    confirmations = case when deposit_record.credited then deposit_record.confirmations
                        when deposit_record.canonical_status = 'REORGED' then excluded.confirmations
                        else greatest(deposit_record.confirmations, excluded.confirmations) end,
                    status = case when deposit_record.credited then 'CREDITED' else excluded.status end,
                    canonical_status = 'CANONICAL', reorged_at = null, reorg_reason = null,
                    tenant_id = coalesce(deposit_record.tenant_id, excluded.tenant_id), account_id = excluded.account_id,
                    raw_payload = excluded.raw_payload, updated_at = excluded.updated_at
                where deposit_record.tenant_id is null or deposit_record.tenant_id = excluded.tenant_id
                """, tenantId, chain, assetSymbol, txHash, logIndex, fromAddress, toAddress, contractAddress,
                amount, blockHeight, blockHash, confirmations, status, accountId, rawPayload, now, now);
    }

    /** 标记充值已经入账。 */
    public int markCredited(String chain, String txHash, long logIndex) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                update deposit_record set credited = true, credited_at = ?, status = 'CREDITED',
                    credit_generation = credit_generation + 1, updated_at = ?
                 where chain = ? and tx_hash = ? and log_index = ?
                   and credited = false and canonical_status = 'CANONICAL'
                """, now, now, chain, txHash, logIndex);
    }

    /** 查询待确认充值。 */
    public List<PendingRecord> listPending(String chain, int requiredConfirmations, int limit) {
        return jdbc.query("""
                select asset_symbol, tx_hash, log_index, from_address, to_address, contract_address, amount,
                       block_height, block_hash, confirmations, account_id, raw_payload
                  from deposit_record
                 where chain = ? and credited = false and canonical_status = 'CANONICAL'
                   and status in ('DETECTED', 'CONFIRMING') and confirmations < ?
                 order by id limit ?
                """, (rs, rowNum) -> new PendingRecord(rs.getString("asset_symbol"), rs.getString("tx_hash"),
                rs.getLong("log_index"), rs.getString("from_address"), rs.getString("to_address"),
                rs.getString("contract_address"), rs.getBigDecimal("amount"), rs.getLong("block_height"),
                rs.getString("block_hash"), rs.getInt("confirmations"), rs.getString("account_id"),
                rs.getString("raw_payload")), chain, requiredConfirmations, limit);
    }

    /** 查询指定区块上需要重组的充值。 */
    public List<ReorgRecord> listForReorg(String chain, long blockHeight, String replacementHash) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, tx_hash, log_index, account_id, to_address, amount,
                       credited, credit_generation, block_height, block_hash
                  from deposit_record
                 where chain = ? and block_height = ? and canonical_status = 'CANONICAL'
                   and block_hash is not null and lower(block_hash) <> lower(?)
                 order by id for update
                """, (rs, rowNum) -> new ReorgRecord(rs.getLong("id"), rs.getObject("tenant_id", UUID.class),
                rs.getString("chain"), rs.getString("asset_symbol"), rs.getString("tx_hash"),
                rs.getLong("log_index"), rs.getString("account_id"), rs.getString("to_address"),
                rs.getBigDecimal("amount"), rs.getBoolean("credited"), rs.getInt("credit_generation"),
                rs.getLong("block_height"), rs.getString("block_hash")), chain, blockHeight, replacementHash);
    }

    /** 查询已经入账且仍处于 canonical 状态的充值区块高度。 */
    public List<Long> listCanonicalBlockHeights(String chain, long minimumHeight) {
        return jdbc.queryForList("""
                select distinct block_height from deposit_record
                 where chain = ? and block_height >= ? and credited = true
                   and canonical_status = 'CANONICAL' and block_hash is not null
                 order by block_height
                """, Long.class, chain, minimumHeight);
    }

    /** 查询指定链已入账充值字段，供服务层按租户和地址组合统计。 */
    public List<java.util.Map<String, Object>> listCreditedForCollectionBalance(String chain) {
        return jdbc.queryForList("""
                select tenant_id, asset_symbol, lower(to_address) as to_address, amount
                  from deposit_record where chain = ? and tenant_id is not null and credited = true
                """, chain);
    }

    /** 将充值标记为重组。 */
    public int markReorged(long id, String reason) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                update deposit_record set credited = false, status = 'REORGED', canonical_status = 'REORGED',
                    confirmations = 0, reorged_at = ?, reorg_reason = ?, updated_at = ?
                 where id = ? and canonical_status = 'CANONICAL'
                """, now, reason, now, id);
    }

    /** 待确认充值数据。 */
    public record PendingRecord(String assetSymbol, String txHash, long logIndex, String fromAddress,
                                String toAddress, String contractAddress, BigDecimal amount, long blockHeight,
                                String blockHash, int confirmations, String accountId, String rawPayload) { }

    /** 重组充值数据。 */
    public record ReorgRecord(long id, UUID tenantId, String chain, String assetSymbol, String txHash,
                              long logIndex, String accountId, String toAddress, BigDecimal amount,
                              boolean credited, int creditGeneration, long blockHeight, String blockHash) { }

}
