package com.surprising.wallet.deposit.repository;

import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.utils.Constants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * UTXO 扫描结果、锁定和花费状态仓储。
 */
@Repository
public class UtxoRepository {
    /**
     * 保存 {@code jdbc}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbc;

    /**
     * 构造 {@code UtxoRepository}，初始化该组件运行所需的状态和依赖。
     */
    public UtxoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入或更新 {@code upsert} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void upsert(String chain, String assetSymbol, String txHash, int vout, String address,
                       BigDecimal amount, long blockHeight, String blockHash,
                       int confirmations, boolean credited) {
        Instant now = Instant.now();
        jdbc.update("""
                        insert into utxo_record(chain, asset_symbol, tx_hash, vout, address, amount, block_height,
                                                block_hash, confirmations, state, credited, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?, ?)
                        on conflict (chain, tx_hash, vout) do update set
                            address = excluded.address,
                            amount = excluded.amount,
                            block_height = excluded.block_height,
                            block_hash = excluded.block_hash,
                            confirmations = greatest(utxo_record.confirmations, excluded.confirmations),
                            state = case
                                when utxo_record.state in ('LOCKED', 'SPENT') then utxo_record.state
                                else excluded.state
                            end,
                            credited = utxo_record.credited or excluded.credited,
                            updated_at = excluded.updated_at
                        """,
                chain, assetSymbol, txHash, vout, address, amount, blockHeight, blockHash,
                confirmations, credited, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 写入或更新 {@code markCredited} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markCredited(String chain, String txHash, int vout) {
        return jdbc.update("""
                        update utxo_record
                        set credited = true, updated_at = ?
                        where chain = ? and tx_hash = ? and vout = ? and credited = false
                        """, Timestamp.from(Instant.now()), chain, txHash, vout);
    }

    /**
     * 执行 {@code lock} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int lock(String chain, String txHash, int vout, String lockRef) {
        return jdbc.update("""
                        update utxo_record
                        set state = 'LOCKED', lock_ref = ?, updated_at = ?
                        where chain = ? and tx_hash = ? and vout = ?
                          and (state = 'AVAILABLE' or (state = 'LOCKED' and lock_ref = ?))
                        """, lockRef, Timestamp.from(Instant.now()), chain, txHash, vout, lockRef);
    }

    /**
     * 执行 {@code lock} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int lock(UUID tenantId, String chain, String txHash, int vout, String lockRef) {
        return jdbc.update("""
                        update utxo_record ur
                           set state = 'LOCKED', lock_ref = ?, updated_at = ?
                         where ur.chain = ? and ur.tx_hash = ? and ur.vout = ?
                           and (ur.state = 'AVAILABLE' or (ur.state = 'LOCKED' and ur.lock_ref = ?))
                           and exists (
                               select 1 from chain_address a
                                where a.tenant_id = ? and a.chain = ur.chain
                                  and lower(a.address) = lower(ur.address) and a.enabled = true
                           )
                        """, lockRef, Timestamp.from(Instant.now()),
                chain, txHash, vout, lockRef, tenantId);
    }

    /**
     * 删除或释放 {@code release} 对应的资源，并收敛相关业务状态。
     */
    public int release(String chain, String lockRef) {
        return jdbc.update("""
                        update utxo_record
                        set state = 'AVAILABLE', lock_ref = null, updated_at = ?
                        where chain = ? and lock_ref = ? and state = 'LOCKED'
                        """, Timestamp.from(Instant.now()), chain, lockRef);
    }

    /**
     * 写入或更新 {@code markSpent} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markSpent(String chain, String lockRef, String spentTxHash) {
        return jdbc.update("""
                        update utxo_record
                        set state = 'SPENT', spent_tx_hash = ?, updated_at = ?
                        where chain = ? and lock_ref = ? and state = 'LOCKED'
                        """, spentTxHash, Timestamp.from(Instant.now()), chain, lockRef);
    }

    /**
     * 设置或更新 {@code updateConfirmations} 对应的状态，并保持相关业务字段一致。
     */
    public int updateConfirmations(String chain, String txHash, int vout, int confirmations) {
        return jdbc.update("""
                        update utxo_record
                        set confirmations = ?, updated_at = ?
                        where chain = ? and tx_hash = ? and vout = ? and state = 'AVAILABLE'
                        """, confirmations, Timestamp.from(Instant.now()), chain, txHash, vout);
    }

    /**
     * 获取或查询 {@code listSpendable} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listSpendable(
            String chain, String assetSymbol, long requiredConfirmations, int limit, int offset) {
        return jdbc.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height, ur.block_hash,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ?
                          and ur.asset_symbol = ?
                          and ur.state = 'AVAILABLE'
                          and ur.confirmations >= ?
                        order by ur.id
                        limit ? offset ?
                        """, (rs, rowNum) -> map(rs, chain),
                chain, assetSymbol, requiredConfirmations, limit, offset);
    }

    /**
     * 获取或查询 {@code listSpendable} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listSpendable(
            UUID tenantId, String chain, String assetSymbol,
            long requiredConfirmations, int limit, int offset) {
        return jdbc.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height, ur.block_hash,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ? and ur.asset_symbol = ?
                          and ur.state = 'AVAILABLE' and ur.confirmations >= ?
                          and exists (
                              select 1 from chain_address a
                               where a.tenant_id = ? and a.chain = ur.chain
                                 and lower(a.address) = lower(ur.address) and a.enabled = true
                          )
                        order by ur.id
                        limit ? offset ?
                        """, (rs, rowNum) -> map(rs, chain),
                chain, assetSymbol, requiredConfirmations, tenantId, limit, offset);
    }

    /**
     * 获取或查询 {@code listAvailableBelowConfirmations} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listAvailableBelowConfirmations(
            String chain, String assetSymbol, long maxConfirmations, long afterId, int limit) {
        return jdbc.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height, ur.block_hash,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ?
                          and ur.asset_symbol = ?
                          and ur.state = 'AVAILABLE'
                          and ur.confirmations < ?
                          and ur.id > ?
                        order by ur.id
                        limit ?
                        """, (rs, rowNum) -> map(rs, chain),
                chain, assetSymbol, maxConfirmations, afterId, limit);
    }

    /**
     * 执行 {@code sumAvailableAmount} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigDecimal sumAvailableAmount(String chain, String assetSymbol) {
        BigDecimal balance = jdbc.queryForObject("""
                        select coalesce(sum(amount), 0)
                        from utxo_record
                        where chain = ?
                          and asset_symbol = ?
                          and state = 'AVAILABLE'
                        """, BigDecimal.class, chain, assetSymbol);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    /**
     * 获取或查询 {@code listByAddress} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listByAddress(String chain, String address, int limit) {
        return jdbc.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height, ur.block_hash,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ?
                          and ur.address = ?
                        order by ur.id desc
                        limit ?
                        """, (rs, rowNum) -> map(rs, chain), chain, address, limit);
    }

    /**
     * 执行 {@code map} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private UtxoTransaction map(ResultSet rs, String chain) throws SQLException {
        int runtimeCurrencyId = rs.getInt("runtime_currency_id");
        if (rs.wasNull()) {
            throw new IllegalStateException(
                    "missing enabled chain_profile.runtime_currency_id for unified UTXO chain " + chain);
        }
        return UtxoTransaction.builder()
                .id(rs.getLong("id"))
                .txId(rs.getString("tx_hash"))
                .seq((short) rs.getInt("vout"))
                .address(rs.getString("address"))
                .balance(rs.getBigDecimal("amount"))
                .blockHeight(rs.getLong("block_height"))
                .blockHash(rs.getString("block_hash"))
                .confirmNum(rs.getLong("confirmations"))
                .spent((byte) 0)
                .spentTxId(Constants.UNSPENT_TX_ID)
                .currency(runtimeCurrencyId)
                .status((byte) Constants.WAITING)
                .credited(rs.getBoolean("credited"))
                .createDate(rs.getTimestamp("created_at"))
                .updateDate(rs.getTimestamp("updated_at"))
                .build();
    }
}
