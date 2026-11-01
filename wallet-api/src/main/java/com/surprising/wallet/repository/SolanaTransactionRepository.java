package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.SolanaTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** sol_transaction 单表仓储。 */
@Repository
public class SolanaTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Solana 交易仓储。 */
    public SolanaTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 Solana 交易。 */
    public int upsert(SolanaTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into sol_transaction(
                    chain, signature, from_address, to_address, asset_symbol, mint_address,
                    amount, fee_lamports, slot, confirmations, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, signature) do update set
                    fee_lamports = case when excluded.slot is not null then excluded.fee_lamports else sol_transaction.fee_lamports end,
                    slot = coalesce(excluded.slot,sol_transaction.slot),
                    confirmations = greatest(sol_transaction.confirmations, excluded.confirmations),
                    status = case when sol_transaction.status in ('CONFIRMED','FAILED') then sol_transaction.status else excluded.status end,
                    raw_payload = coalesce(sol_transaction.raw_payload,excluded.raw_payload), updated_at = excluded.updated_at
                """, tx.getChain(), tx.getSignature(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getMintAddress(), tx.getAmount(), tx.getFeeLamports(), tx.getSlot(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), now, now);
    }

    public Optional<SolanaTransactionRecord> findSigned(String signature) {
        return jdbc.query("select * from sol_transaction where chain='SOLANA' and signature=? and raw_payload is not null",
                (rs,n)->SolanaTransactionRecord.builder().chain("SOLANA").signature(rs.getString("signature"))
                        .fromAddress(rs.getString("from_address")).toAddress(rs.getString("to_address"))
                        .assetSymbol(rs.getString("asset_symbol")).mintAddress(rs.getString("mint_address"))
                        .amount(rs.getBigDecimal("amount")).feeLamports(rs.getObject("fee_lamports",Long.class))
                        .slot(rs.getObject("slot",Long.class)).status(rs.getString("status")).rawPayload(rs.getString("raw_payload")).build(),signature).stream().findFirst();
    }
    public int markSubmitted(String signature) {
        return jdbc.update("update sol_transaction set status='SENT',updated_at=now() where chain='SOLANA' and signature=? and status='PREPARED'",signature);
    }

    /** 查询已确认交易的原子手续费。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String signature) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_lamports from sol_transaction
                 where chain = ? and signature = ? and status in ('CONFIRMED','FAILED') and slot is not null
                 limit 1
                """, BigDecimal.class, chain, signature);
        return rows.stream().findFirst();
    }
}
