package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.PolkadotTransactionRecord;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** polkadot_transaction single-table storage of original wire, finalized proof and exact native fee. */
@Repository
@RequiredArgsConstructor
public class PolkadotTransactionRepository {
    private final JdbcTemplate jdbc;

    public int insertPending(UUID requestId, UUID tenantId, String role, String sender, int nativeDecimals) {
        return jdbc.update("""
                insert into polkadot_transaction(request_id,tenant_id,role,sender,native_decimals,status)
                values (?,?,?,?,?,'SIGNING') on conflict(request_id) do nothing
                """,requestId,tenantId,role,sender,nativeDecimals);
    }
    public Optional<PolkadotTransactionRecord> find(UUID tenantId,UUID requestId) {
        return jdbc.query("select * from polkadot_transaction where tenant_id=? and request_id=?",
                PolkadotTransactionRepository::map,tenantId,requestId).stream().findFirst();
    }
    public boolean hasPendingSender(String role,String sender) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from polkadot_transaction where role=? and sender=?
                    and status in ('SIGNING','PREPARED','SENT','CONFIRMING'))
                """,Boolean.class,role,sender));
    }
    public int bindSigned(UUID tenantId,UUID requestId,String hash,String raw) {
        return jdbc.update("""
                update polkadot_transaction set tx_hash=?,raw_payload=?,status='PREPARED',updated_at=now()
                 where tenant_id=? and request_id=? and tx_hash is null and raw_payload is null and status='SIGNING'
                """,hash,raw,tenantId,requestId);
    }
    public int markSubmitted(UUID tenantId,UUID requestId) {
        return jdbc.update("""
                update polkadot_transaction set status='SENT',updated_at=now()
                 where tenant_id=? and request_id=? and raw_payload is not null and status in ('PREPARED','SENT')
                """,tenantId,requestId);
    }
    public int recordProof(UUID tenantId,UUID requestId,String proof,BigInteger fee,long height,String status) {
        return jdbc.update("""
                update polkadot_transaction set execution_receipt=coalesce(execution_receipt,?::jsonb),
                    fee_planck=?,block_height=coalesce(block_height,?),status=?,updated_at=now()
                 where tenant_id=? and request_id=? and raw_payload is not null
                   and status in ('PREPARED','SENT','CONFIRMING')
                """,proof,new BigDecimal(fee),height,status,tenantId,requestId);
    }
    public Optional<BigDecimal> findConfirmedFee(String hash) {
        return jdbc.query("""
                select fee_planck,native_decimals from polkadot_transaction where tx_hash=?
                 and status in ('CONFIRMED','FAILED') and execution_receipt is not null and fee_planck is not null
                """,(row,index)->row.getBigDecimal("fee_planck").movePointLeft(row.getInt("native_decimals")),hash)
                .stream().findFirst();
    }
    private static PolkadotTransactionRecord map(ResultSet row,int index)throws SQLException {
        var fee=row.getBigDecimal("fee_planck");
        return new PolkadotTransactionRecord(row.getObject("request_id",UUID.class),row.getObject("tenant_id",UUID.class),
                row.getString("role"),row.getString("sender"),row.getString("tx_hash"),row.getString("status"),
                row.getString("raw_payload"),fee==null?null:fee.toBigIntegerExact(),row.getInt("native_decimals"),
                row.getObject("block_height",Long.class),row.getString("execution_receipt"));
    }
}
