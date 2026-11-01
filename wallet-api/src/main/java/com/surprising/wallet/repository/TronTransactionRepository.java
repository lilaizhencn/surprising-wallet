package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.TronTransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** tron_tx 单表仓储。 */
@Repository
public class TronTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 TRON 交易仓储。 */
    public TronTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 TRON 交易。 */
    public int upsert(TronTransactionRecord tx) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into tron_tx(chain, tx_hash, from_address, to_address, asset_symbol, contract_address,
                                    amount, fee, block_height, confirmations, status, raw_payload,
                                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee = excluded.fee, block_height = coalesce(excluded.block_height, tron_tx.block_height),
                    confirmations = greatest(excluded.confirmations,tron_tx.confirmations),
                    status = case when tron_tx.status in ('CONFIRMED','FAILED') then tron_tx.status else excluded.status end,
                    raw_payload = coalesce(tron_tx.raw_payload, excluded.raw_payload), updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getBlockHeight(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), now, now);
    }

    /** 查询已确认交易的网络费用。 */
    public Optional<BigDecimal> findConfirmedFee(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee from tron_tx
                 where chain = ? and tx_hash = ? and status in ('CONFIRMED','FAILED')
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }

    public Optional<TronTransactionRecord> findSigned(String hash) {
        return jdbc.query("""
                select chain,tx_hash,from_address,to_address,asset_symbol,contract_address,amount,fee,
                       block_height,confirmations,status,raw_payload from tron_tx
                 where chain='TRON' and tx_hash=? and raw_payload is not null
                """, (rs,row) -> TronTransactionRecord.builder().chain(rs.getString("chain")).txHash(rs.getString("tx_hash"))
                .fromAddress(rs.getString("from_address")).toAddress(rs.getString("to_address")).assetSymbol(rs.getString("asset_symbol"))
                .contractAddress(rs.getString("contract_address")).amount(rs.getBigDecimal("amount")).fee(rs.getBigDecimal("fee"))
                .blockHeight((Long)rs.getObject("block_height")).confirmations(rs.getInt("confirmations"))
                .status(rs.getString("status")).rawPayload(rs.getString("raw_payload")).build(),hash).stream().findFirst();
    }

    public int markSubmitted(String hash) {
        return jdbc.update("update tron_tx set status='SENT',updated_at=now() where chain='TRON' and tx_hash=? and status='PREPARED' and raw_payload is not null",hash);
    }
}
