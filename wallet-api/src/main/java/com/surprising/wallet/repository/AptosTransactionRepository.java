package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.AptosTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** aptos_transaction 单表仓储。 */
@Repository
public class AptosTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Aptos 交易仓储。 */
    public AptosTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 Aptos 交易。 */
    public int upsert(AptosTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into aptos_transaction(
                    chain, tx_hash, sender, receiver, asset_symbol, coin_type,
                    amount, gas_used, gas_unit_price, version, sequence_number,
                    confirmations, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    gas_used = greatest(aptos_transaction.gas_used, excluded.gas_used),
                    gas_unit_price = greatest(aptos_transaction.gas_unit_price, excluded.gas_unit_price),
                    version = coalesce(excluded.version, aptos_transaction.version),
                    sequence_number = coalesce(excluded.sequence_number, aptos_transaction.sequence_number),
                    confirmations = greatest(aptos_transaction.confirmations, excluded.confirmations),
                    status = excluded.status, raw_payload = coalesce(excluded.raw_payload, aptos_transaction.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(),
                tx.getCoinType(), tx.getAmount(), tx.getGasUsed(), tx.getGasUnitPrice(), tx.getVersion(),
                tx.getSequenceNumber(), tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 标记 Aptos 交易已确认。 */
    public int markConfirmed(String chain, String txHash, long version, long gasUsed,
                             long gasUnitPrice, String rawPayload) {
        return jdbc.update("""
                update aptos_transaction
                   set confirmations = greatest(confirmations, 1), status = 'CONFIRMED',
                       version = ?, gas_used = ?, gas_unit_price = ?,
                       raw_payload = coalesce(?, raw_payload), updated_at = ?
                 where chain = ? and tx_hash = ? and status <> 'CONFIRMED'
                """, version, gasUsed, gasUnitPrice, rawPayload, Timestamp.from(Instant.now()), chain, txHash);
    }

    /** 查询已确认交易的原子 Gas 费用。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select gas_used::numeric * gas_unit_price::numeric
                  from aptos_transaction
                 where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
