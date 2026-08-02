package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.TonTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** ton_transaction 单表仓储。 */
@Repository
public class TonTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 TON 交易仓储。 */
    public TonTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 TON 交易。 */
    public int upsert(TonTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into ton_transaction(
                    chain, tx_hash, from_address, to_address, asset_symbol, jetton_master,
                    amount, fee_nano, logical_time, confirmations, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee_nano = excluded.fee_nano, logical_time = excluded.logical_time,
                    confirmations = case when ton_transaction.status = 'CONFIRMED'
                        then ton_transaction.confirmations else greatest(ton_transaction.confirmations, excluded.confirmations) end,
                    status = case when ton_transaction.status = 'CONFIRMED'
                        then ton_transaction.status else excluded.status end,
                    raw_payload = excluded.raw_payload, updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getJettonMaster(), tx.getAmount(), tx.getFeeNano(), tx.getLogicalTime(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 更新 TON 充值确认状态。 */
    public int updateDepositConfirmations(String chain, String txHash, int confirmations,
                                          int requiredConfirmations) {
        return jdbc.update("""
                update ton_transaction
                   set confirmations = ?, status = case when ? >= ? then 'CONFIRMED' else 'CONFIRMING' end,
                       updated_at = ?
                 where chain = ? and tx_hash = ? and status <> 'CONFIRMED'
                """, confirmations, confirmations, requiredConfirmations, Timestamp.from(Instant.now()), chain, txHash);
    }

    /** 标记 TON 交易已确认。 */
    public int markConfirmed(String chain, String txHash) {
        return jdbc.update("""
                update ton_transaction
                   set confirmations = greatest(confirmations, 1), status = 'CONFIRMED', updated_at = ?
                 where chain = ? and tx_hash = ? and status <> 'CONFIRMED'
                """, Timestamp.from(Instant.now()), chain, txHash);
    }

    /** 查询 TON 原始交易载荷。 */
    public Optional<String> findRawPayload(String chain, String txHash) {
        List<String> rows = jdbc.queryForList("""
                select raw_payload from ton_transaction
                 where chain = ? and tx_hash = ? and raw_payload is not null
                """, String.class, chain, txHash);
        return rows.stream().findFirst();
    }

    /** 查询已确认交易的原子手续费。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_nano from ton_transaction
                 where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
