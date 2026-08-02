package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.StarknetTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** starknet_transaction 单表仓储。 */
@Repository
public class StarknetTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Starknet 交易仓储。 */
    public StarknetTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 Starknet 交易。 */
    public int upsert(StarknetTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into starknet_transaction(chain, tx_hash, from_address, to_address, asset_symbol,
                                                  contract_address, amount, fee, block_height, confirmations,
                                                  status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    from_address = coalesce(excluded.from_address, starknet_transaction.from_address),
                    to_address = coalesce(excluded.to_address, starknet_transaction.to_address),
                    asset_symbol = excluded.asset_symbol,
                    contract_address = coalesce(excluded.contract_address, starknet_transaction.contract_address),
                    amount = excluded.amount,
                    fee = coalesce(excluded.fee, starknet_transaction.fee),
                    block_height = coalesce(excluded.block_height, starknet_transaction.block_height),
                    confirmations = excluded.confirmations,
                    status = excluded.status,
                    raw_payload = coalesce(excluded.raw_payload, starknet_transaction.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getBlockHeight(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 将指定交易更新为已确认并保存实际手续费。 */
    public int markConfirmed(String chain, String txHash, BigDecimal fee, Long blockHeight, int confirmations,
                             String rawPayload) {
        return jdbc.update("""
                update starknet_transaction
                   set fee = ?, block_height = ?, confirmations = ?, status = 'CONFIRMED',
                       raw_payload = coalesce(?, raw_payload), updated_at = ?
                 where chain = ? and tx_hash = ?
                """, fee, blockHeight, confirmations, rawPayload, Timestamp.from(Instant.now()), chain, txHash);
    }

    /** 查询已经确认的 Starknet 交易手续费。 */
    public Optional<BigDecimal> findConfirmedFee(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee from starknet_transaction
                 where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
