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
                    fee_lamports = excluded.fee_lamports, slot = excluded.slot,
                    confirmations = greatest(sol_transaction.confirmations, excluded.confirmations),
                    status = excluded.status, raw_payload = excluded.raw_payload, updated_at = excluded.updated_at
                """, tx.getChain(), tx.getSignature(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getMintAddress(), tx.getAmount(), tx.getFeeLamports(), tx.getSlot(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 查询已确认交易的原子手续费。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String signature) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_lamports from sol_transaction
                 where chain = ? and signature = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, signature);
        return rows.stream().findFirst();
    }
}
