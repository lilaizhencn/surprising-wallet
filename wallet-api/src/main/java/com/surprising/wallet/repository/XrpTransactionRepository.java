package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.XrpTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** xrp_transaction 单表仓储。 */
@Repository
public class XrpTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 XRP 交易仓储。 */
    public XrpTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 XRP 交易。 */
    public int upsert(XrpTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into xrp_transaction(
                    chain, tx_hash, from_address, to_address, asset_symbol, issuer_address, currency_code,
                    amount, fee_drops, ledger_index, sequence_number, confirmations, status, raw_payload,
                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee_drops = coalesce(excluded.fee_drops, xrp_transaction.fee_drops),
                    ledger_index = coalesce(excluded.ledger_index, xrp_transaction.ledger_index),
                    sequence_number = coalesce(excluded.sequence_number, xrp_transaction.sequence_number),
                    confirmations = greatest(xrp_transaction.confirmations, excluded.confirmations),
                    status = excluded.status, raw_payload = coalesce(excluded.raw_payload, xrp_transaction.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getIssuerAddress(), tx.getCurrencyCode(), tx.getAmount(), tx.getFeeDrops(), tx.getLedgerIndex(),
                tx.getSequenceNumber(), tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 查询 XRP 交易资产。 */
    public Optional<String> findAssetSymbol(String chain, String txHash) {
        List<String> rows = jdbc.queryForList("""
                select asset_symbol from xrp_transaction where chain = ? and tx_hash = ? limit 1
                """, String.class, chain, txHash);
        return rows.stream().findFirst();
    }

    /** 查询已确认交易的原子手续费。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_drops from xrp_transaction
                 where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
