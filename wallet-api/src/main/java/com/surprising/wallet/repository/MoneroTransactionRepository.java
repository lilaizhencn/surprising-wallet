package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.MoneroTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** monero_transaction 单表仓储。 */
@Repository
public class MoneroTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Monero 交易仓储。 */
    public MoneroTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 Monero 交易。 */
    public int upsert(MoneroTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into monero_transaction(
                    chain, tx_hash, direction, account_index, subaddress_index, address, asset_symbol,
                    amount, fee_atomic, block_height, confirmations, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash, direction, subaddress_index) do update set
                    amount = coalesce(excluded.amount, monero_transaction.amount),
                    fee_atomic = coalesce(excluded.fee_atomic, monero_transaction.fee_atomic),
                    block_height = coalesce(excluded.block_height, monero_transaction.block_height),
                    confirmations = greatest(monero_transaction.confirmations, excluded.confirmations),
                    status = excluded.status, raw_payload = coalesce(excluded.raw_payload, monero_transaction.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getDirection(), tx.getAccountIndex(),
                tx.getSubaddressIndex(), tx.getAddress(), tx.getAssetSymbol(), tx.getAmount(), tx.getFeeAtomic(),
                tx.getBlockHeight(), tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 查询已确认交易的原子手续费。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_atomic from monero_transaction
                 where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                 order by updated_at desc limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
