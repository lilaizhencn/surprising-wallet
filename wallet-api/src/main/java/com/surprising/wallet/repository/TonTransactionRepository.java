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
                    amount, fee_nano, logical_time, confirmations, status, raw_payload, execution_receipt, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee_nano = case when ton_transaction.status in ('CONFIRMED','FAILED') then ton_transaction.fee_nano else excluded.fee_nano end,
                    logical_time = coalesce(ton_transaction.logical_time, excluded.logical_time),
                    confirmations = case when ton_transaction.status in ('CONFIRMED','FAILED')
                        then ton_transaction.confirmations else greatest(ton_transaction.confirmations, excluded.confirmations) end,
                    status = case when ton_transaction.status in ('CONFIRMED','FAILED')
                        then ton_transaction.status else excluded.status end,
                    raw_payload = coalesce(ton_transaction.raw_payload, excluded.raw_payload),
                    execution_receipt = coalesce(ton_transaction.execution_receipt, excluded.execution_receipt),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getJettonMaster(), tx.getAmount(), tx.getFeeNano(), tx.getLogicalTime(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), tx.getExecutionReceipt(), now, now);
    }

    /** 更新 TON 充值确认状态。 */
    public int updateDepositConfirmations(String chain, String txHash, int confirmations,
                                          int requiredConfirmations) {
        return jdbc.update("""
                update ton_transaction
                   set confirmations = ?, status = case when ? >= ? then 'CONFIRMED' else 'CONFIRMING' end,
                       updated_at = ?
                 where chain = ? and tx_hash = ? and status not in ('CONFIRMED','FAILED')
                """, confirmations, confirmations, requiredConfirmations, Timestamp.from(Instant.now()), chain, txHash);
    }

    /** 查询 TON 原始交易载荷。 */
    public Optional<String> findRawPayload(String chain, String txHash) {
        List<String> rows = jdbc.queryForList("""
                select raw_payload from ton_transaction
                 where chain = ? and tx_hash = ? and raw_payload is not null
                """, String.class, chain, txHash);
        return rows.stream().findFirst();
    }

    public Optional<TonTransactionRecord> findSigned(String hash) {
        return jdbc.query("""
                select * from ton_transaction where chain='TON' and tx_hash=? and raw_payload is not null
                """, (rs,index) -> TonTransactionRecord.builder().chain(rs.getString("chain")).txHash(rs.getString("tx_hash"))
                .fromAddress(rs.getString("from_address")).toAddress(rs.getString("to_address"))
                .assetSymbol(rs.getString("asset_symbol")).jettonMaster(rs.getString("jetton_master"))
                .amount(rs.getBigDecimal("amount")).feeNano(rs.getLong("fee_nano"))
                .logicalTime(rs.getBigDecimal("logical_time")==null?null:rs.getBigDecimal("logical_time").toBigIntegerExact())
                .confirmations(rs.getInt("confirmations")).status(rs.getString("status")).rawPayload(rs.getString("raw_payload"))
                .executionReceipt(rs.getString("execution_receipt")).build(),hash).stream().findFirst();
    }

    public int markSubmitted(String hash) {
        return jdbc.update("""
                update ton_transaction set status='SENT',updated_at=now()
                 where chain='TON' and tx_hash=? and status not in ('CONFIRMED','FAILED')
                """,hash);
    }

    /** 查询已确认交易的原子手续费。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_nano from ton_transaction
                 where chain = ? and tx_hash = ? and status in ('CONFIRMED','FAILED')
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
