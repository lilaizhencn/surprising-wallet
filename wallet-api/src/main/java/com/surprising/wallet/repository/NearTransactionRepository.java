package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.NearTransactionRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** near_transaction single-table storage; first selected wire, proof and terminal fees survive late observations. */
@Repository
@RequiredArgsConstructor
public class NearTransactionRepository {
    private final JdbcTemplate jdbc;

    public int insertPending(NearTransactionRecord tx) {
        return jdbc.update("""
                insert into near_transaction(chain,tx_hash,action_index,sender,receiver,asset_symbol,amount,status)
                values (?,?,0,?,?,?,?,'SIGNING') on conflict(chain,tx_hash,action_index) do nothing
                """,tx.getChain(),tx.getTxHash(),tx.getSender(),tx.getReceiver(),tx.getAssetSymbol(),tx.getAmount());
    }
    public int upsert(NearTransactionRecord tx) {
        return jdbc.update("""
                insert into near_transaction(chain,tx_hash,action_index,sender,receiver,asset_symbol,amount,
                                             gas_burnt,fee_yocto,block_height,status,raw_payload,execution_receipt)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                on conflict(chain,tx_hash,action_index) do update set
                    gas_burnt=case when near_transaction.status in ('CONFIRMED','FAILED') then near_transaction.gas_burnt else excluded.gas_burnt end,
                    fee_yocto=case when near_transaction.status in ('CONFIRMED','FAILED') then near_transaction.fee_yocto else excluded.fee_yocto end,
                    block_height=coalesce(near_transaction.block_height,excluded.block_height),
                    status=case when near_transaction.status in ('CONFIRMED','FAILED') then near_transaction.status else excluded.status end,
                    raw_payload=coalesce(near_transaction.raw_payload,excluded.raw_payload),
                    execution_receipt=coalesce(near_transaction.execution_receipt,excluded.execution_receipt),updated_at=now()
                """,tx.getChain(),tx.getTxHash(),tx.getActionIndex()==null?0L:tx.getActionIndex(),tx.getSender(),tx.getReceiver(),tx.getAssetSymbol(),tx.getAmount(),
                tx.getGasBurnt()==null?0L:tx.getGasBurnt(),tx.getFeeYocto()==null?null:new BigDecimal(tx.getFeeYocto()),tx.getBlockHeight(),tx.getStatus(),tx.getRawPayload(),tx.getExecutionReceipt());
    }
    public Optional<NearTransactionRecord> find(String hash) {
        return jdbc.query("select * from near_transaction where chain='NEAR' and tx_hash=? and action_index=0",NearTransactionRepository::map,hash).stream().findFirst();
    }
    public boolean hasPendingSender(String sender) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from near_transaction where chain='NEAR' and sender=?
                    and status in ('SIGNING','PREPARED','SENT','CONFIRMING'))
                """,Boolean.class,sender));
    }
    public int bindSigned(String hash,String raw) {
        return jdbc.update("""
                update near_transaction set raw_payload=?,status='PREPARED',updated_at=now()
                 where chain='NEAR' and tx_hash=? and action_index=0 and raw_payload is null and status='SIGNING'
                """,raw,hash);
    }
    public int markSubmitted(String hash) {
        return jdbc.update("""
                update near_transaction set status='SENT',updated_at=now()
                 where chain='NEAR' and tx_hash=? and action_index=0 and raw_payload is not null and status in ('PREPARED','SENT')
                """,hash);
    }
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain,String hash) {
        return jdbc.queryForList("""
                select fee_yocto from near_transaction where chain=? and tx_hash=? and action_index=0
                    and status in ('CONFIRMED','FAILED') and fee_yocto is not null and execution_receipt is not null
                """,BigDecimal.class,chain,hash).stream().findFirst();
    }
    public int confirmDeposit(String hash) {
        return jdbc.update("update near_transaction set status='CONFIRMED',updated_at=now() where chain='NEAR' and tx_hash=? and status='CONFIRMING' and raw_payload is null",hash);
    }
    private static NearTransactionRecord map(ResultSet row,int index)throws SQLException {
        var fee=row.getBigDecimal("fee_yocto");
        return NearTransactionRecord.builder().chain(row.getString("chain")).txHash(row.getString("tx_hash")).actionIndex(row.getLong("action_index"))
                .sender(row.getString("sender")).receiver(row.getString("receiver")).assetSymbol(row.getString("asset_symbol")).amount(row.getBigDecimal("amount"))
                .gasBurnt(row.getLong("gas_burnt")).feeYocto(fee==null?null:fee.toBigIntegerExact()).blockHeight(row.getObject("block_height",Long.class))
                .status(row.getString("status")).rawPayload(row.getString("raw_payload")).executionReceipt(row.getString("execution_receipt")).build();
    }
}
