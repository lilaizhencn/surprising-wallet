package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.EvmTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** evm_tx 单表仓储。 */
@Repository
public class EvmTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EVM 交易仓储。 */
    public EvmTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 EVM 交易。 */
    public int upsert(EvmTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol, contract_address,
                                   amount, fee, nonce, block_height, confirmations, status, raw_payload,
                                   created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee = excluded.fee, block_height = coalesce(excluded.block_height, evm_tx.block_height),
                    confirmations = excluded.confirmations, status = excluded.status,
                    raw_payload = coalesce(excluded.raw_payload, evm_tx.raw_payload), updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getNonce(), tx.getBlockHeight(),
                tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 查询已确认交易的网络费用。 */
    public Optional<BigDecimal> findConfirmedFee(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee from evm_tx
                 where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
