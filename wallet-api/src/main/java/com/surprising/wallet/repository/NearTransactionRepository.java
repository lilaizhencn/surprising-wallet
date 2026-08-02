package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.NearTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** near_transaction 单表仓储。 */
@Repository
public class NearTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 NEAR 交易仓储。 */
    public NearTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 NEAR 交易。 */
    public int upsert(NearTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into near_transaction(
                    chain, tx_hash, action_index, sender, receiver, asset_symbol,
                    amount, gas_burnt, block_height, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash, action_index) do update set
                    sender = excluded.sender, receiver = excluded.receiver, asset_symbol = excluded.asset_symbol,
                    amount = excluded.amount, gas_burnt = greatest(near_transaction.gas_burnt, excluded.gas_burnt),
                    block_height = greatest(coalesce(near_transaction.block_height, 0), coalesce(excluded.block_height, 0)),
                    status = excluded.status, raw_payload = coalesce(excluded.raw_payload, near_transaction.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getActionIndex() == null ? 0L : tx.getActionIndex(),
                tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(), tx.getAmount(), tx.getGasBurnt(),
                tx.getBlockHeight(), tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 标记 NEAR 交易已确认。 */
    public int markConfirmed(String chain, String txHash, long blockHeight, long gasBurnt, String rawPayload) {
        return jdbc.update("""
                update near_transaction
                   set status = 'CONFIRMED', block_height = greatest(coalesce(block_height, 0), ?),
                       gas_burnt = greatest(gas_burnt, ?), raw_payload = coalesce(?, raw_payload), updated_at = ?
                 where chain = ? and tx_hash = ?
                """, blockHeight, gasBurnt, rawPayload, Timestamp.from(Instant.now()), chain, txHash);
    }

    /** 查询 NEAR 交易发送方。 */
    public java.util.Optional<String> findSender(String chain, String txHash) {
        List<String> rows = jdbc.queryForList("""
                select sender from near_transaction where chain = ? and tx_hash = ?
                """, String.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
