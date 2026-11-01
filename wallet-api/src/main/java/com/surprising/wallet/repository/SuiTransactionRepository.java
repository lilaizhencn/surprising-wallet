package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.SuiTransactionRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** sui_transaction single-table storage. Fixed wire and terminal expense are immutable. */
@Repository
@RequiredArgsConstructor
public class SuiTransactionRepository {
    private final JdbcTemplate jdbc;

    public int insertPending(SuiTransactionRecord tx) {
        return jdbc.update("""
                insert into sui_transaction(chain,tx_digest,sender,receiver,asset_symbol,coin_type,amount,status)
                values (?,?,?,?,?,?,?,'SIGNING') on conflict(chain,tx_digest) do nothing
                """,tx.getChain(),tx.getTxDigest(),tx.getSender(),tx.getReceiver(),tx.getAssetSymbol(),tx.getCoinType(),tx.getAmount());
    }
    public int upsert(SuiTransactionRecord tx) {
        return jdbc.update("""
                insert into sui_transaction(chain,tx_digest,sender,receiver,asset_symbol,coin_type,amount,
                                            fee_mist,checkpoint,status,raw_payload,execution_receipt)
                values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                on conflict(chain,tx_digest) do update set
                    fee_mist=case when sui_transaction.status in ('CONFIRMED','FAILED') then sui_transaction.fee_mist else excluded.fee_mist end,
                    checkpoint=coalesce(sui_transaction.checkpoint,excluded.checkpoint),
                    status=case when sui_transaction.status in ('CONFIRMED','FAILED') then sui_transaction.status else excluded.status end,
                    raw_payload=coalesce(sui_transaction.raw_payload,excluded.raw_payload),
                    execution_receipt=coalesce(sui_transaction.execution_receipt,excluded.execution_receipt),updated_at=now()
                """,tx.getChain(),tx.getTxDigest(),tx.getSender(),tx.getReceiver(),tx.getAssetSymbol(),tx.getCoinType(),tx.getAmount(),
                tx.getFeeMist()==null?0L:tx.getFeeMist(),tx.getCheckpoint(),tx.getStatus(),tx.getRawPayload(),tx.getExecutionReceipt());
    }
    public Optional<SuiTransactionRecord> find(String digest) {
        return jdbc.query("select * from sui_transaction where chain='SUI' and tx_digest=?",SuiTransactionRepository::map,digest).stream().findFirst();
    }
    public boolean hasPendingSender(String sender) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from sui_transaction where chain='SUI' and sender=?
                    and status in ('SIGNING','PREPARED','SENT','CONFIRMING'))
                """,Boolean.class,sender));
    }
    public int bindSigned(String digest,String raw) {
        return jdbc.update("""
                update sui_transaction set raw_payload=?,status='PREPARED',updated_at=now()
                 where chain='SUI' and tx_digest=? and raw_payload is null and status='SIGNING'
                """,raw,digest);
    }
    public int markSubmitted(String digest) {
        return jdbc.update("""
                update sui_transaction set status='SENT',updated_at=now()
                 where chain='SUI' and tx_digest=? and raw_payload is not null and status in ('PREPARED','SENT')
                """,digest);
    }
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain,String digest) {
        return jdbc.queryForList("""
                select fee_mist from sui_transaction where chain=? and tx_digest=? and status in ('CONFIRMED','FAILED')
                """,BigDecimal.class,chain,digest).stream().findFirst();
    }
    public int confirmDeposit(String digest) {
        return jdbc.update("update sui_transaction set status='CONFIRMED',updated_at=now() where chain='SUI' and tx_digest=? and status='CONFIRMING' and raw_payload is null",digest);
    }
    private static SuiTransactionRecord map(ResultSet row,int index)throws SQLException {
        return SuiTransactionRecord.builder().chain(row.getString("chain")).txDigest(row.getString("tx_digest"))
                .sender(row.getString("sender")).receiver(row.getString("receiver")).assetSymbol(row.getString("asset_symbol"))
                .coinType(row.getString("coin_type")).amount(row.getBigDecimal("amount")).feeMist(row.getLong("fee_mist"))
                .checkpoint(row.getObject("checkpoint",Long.class)).status(row.getString("status"))
                .rawPayload(row.getString("raw_payload")).executionReceipt(row.getString("execution_receipt")).build();
    }
}
