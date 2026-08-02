package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.SuiTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** sui_transaction 单表仓储。 */
@Repository
public class SuiTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Sui 交易仓储。 */
    public SuiTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 Sui 交易。 */
    public int upsert(SuiTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into sui_transaction(
                    chain, tx_digest, sender, receiver, asset_symbol, coin_type,
                    amount, gas_used, checkpoint, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_digest) do update set
                    gas_used = greatest(sui_transaction.gas_used, excluded.gas_used),
                    checkpoint = coalesce(excluded.checkpoint, sui_transaction.checkpoint),
                    status = excluded.status, raw_payload = coalesce(excluded.raw_payload, sui_transaction.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxDigest(), tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(),
                tx.getCoinType(), tx.getAmount(), tx.getGasUsed(), tx.getCheckpoint(), tx.getStatus(),
                tx.getRawPayload(), now, now);
    }

    /** 标记 Sui 交易已确认。 */
    public int markConfirmed(String chain, String txDigest, long checkpoint, long gasUsed, String rawPayload) {
        return jdbc.update("""
                update sui_transaction
                   set status = 'CONFIRMED', checkpoint = ?, gas_used = ?,
                       raw_payload = coalesce(?, raw_payload), updated_at = ?
                 where chain = ? and tx_digest = ? and status <> 'CONFIRMED'
                """, checkpoint, gasUsed, rawPayload, Timestamp.from(Instant.now()), chain, txDigest);
    }

    /** 查询已确认交易的原子 Gas 费用。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txDigest) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select gas_used from sui_transaction
                 where chain = ? and tx_digest = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, txDigest);
        return rows.stream().findFirst();
    }
}
