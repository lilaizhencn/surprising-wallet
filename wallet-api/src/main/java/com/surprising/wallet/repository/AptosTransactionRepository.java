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
                    amount, gas_used, gas_unit_price, fee_octas, version, sequence_number,
                    confirmations, status, raw_payload, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    gas_used = greatest(aptos_transaction.gas_used, excluded.gas_used),
                    gas_unit_price = greatest(aptos_transaction.gas_unit_price, excluded.gas_unit_price),
                    fee_octas = case when excluded.version is not null then excluded.fee_octas else aptos_transaction.fee_octas end,
                    version = coalesce(excluded.version, aptos_transaction.version),
                    sequence_number = coalesce(excluded.sequence_number, aptos_transaction.sequence_number),
                    confirmations = greatest(aptos_transaction.confirmations, excluded.confirmations),
                    status = case when aptos_transaction.status in ('CONFIRMED','FAILED') then aptos_transaction.status else excluded.status end,
                    raw_payload = coalesce(aptos_transaction.raw_payload, excluded.raw_payload),
                    updated_at = excluded.updated_at
                """, tx.getChain(), tx.getTxHash(), tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(),
                tx.getCoinType(), tx.getAmount(), tx.getGasUsed(), tx.getGasUnitPrice(), tx.getFeeOctas()==null?0L:tx.getFeeOctas(), tx.getVersion(),
                tx.getSequenceNumber(), tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), now, now);
    }

    public Optional<AptosTransactionRecord> findSigned(String hash) {
        return jdbc.query("select * from aptos_transaction where chain='APTOS' and tx_hash=? and raw_payload is not null",
                (rs,n)->AptosTransactionRecord.builder().chain("APTOS").txHash(rs.getString("tx_hash")).sender(rs.getString("sender")).receiver(rs.getString("receiver"))
                        .assetSymbol(rs.getString("asset_symbol")).coinType(rs.getString("coin_type")).amount(rs.getBigDecimal("amount"))
                        .gasUsed(rs.getLong("gas_used")).gasUnitPrice(rs.getLong("gas_unit_price")).feeOctas(rs.getLong("fee_octas"))
                        .version(rs.getObject("version",Long.class)).sequenceNumber(rs.getLong("sequence_number")).status(rs.getString("status")).rawPayload(rs.getString("raw_payload")).build(),hash).stream().findFirst();
    }
    public int markSubmitted(String hash){return jdbc.update("update aptos_transaction set status='SENT',updated_at=now() where chain='APTOS' and tx_hash=? and status='PREPARED'",hash);}

    /** 查询已确认交易的原子 Gas 费用。 */
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain, String txHash) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select fee_octas::numeric
                  from aptos_transaction
                 where chain = ? and tx_hash = ? and status in ('CONFIRMED','FAILED') and version is not null
                 limit 1
                """, BigDecimal.class, chain, txHash);
        return rows.stream().findFirst();
    }
}
