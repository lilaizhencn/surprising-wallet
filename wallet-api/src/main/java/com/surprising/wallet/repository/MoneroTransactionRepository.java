package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.MoneroTransactionRecord;
import com.surprising.wallet.chain.monero.MoneroSignedPayment;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** monero_transaction single-table repository. Opaque transaction material stays in the encrypted MPC result. */
@Repository
public class MoneroTransactionRepository {
    private static final String COLUMNS="tenant_id,request_id,sender,tx_hash,selected_attempt_id,artifact_hash,fee_atomic,status,block_height,block_hash,confirmations,execution_receipt::text";
    private final JdbcTemplate jdbc;
    public MoneroTransactionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public int insertPending(UUID tenant,UUID request,String sender) {
        return jdbc.update("insert into monero_transaction(tenant_id,request_id,sender,status) values (?,?,?,'SIGNING') on conflict(tenant_id,request_id) do nothing",tenant,request,sender);
    }
    public Optional<MoneroTransactionRecord> find(UUID tenant,UUID request) {
        return jdbc.query("select "+COLUMNS+" from monero_transaction where tenant_id=? and request_id=?",(rs,row)->map(rs),tenant,request).stream().findFirst();
    }
    public Optional<MoneroTransactionRecord> findByHash(UUID tenant,String hash) {
        return jdbc.query("select "+COLUMNS+" from monero_transaction where tenant_id=? and tx_hash=?",(rs,row)->map(rs),tenant,hash).stream().findFirst();
    }
    public boolean hasPendingSender(UUID tenant,String sender) {
        return !jdbc.queryForList("select 1 from monero_transaction where tenant_id=? and sender=? and status<>'CONFIRMED' limit 1",Integer.class,tenant,sender).isEmpty();
    }
    public int bindSigned(UUID tenant,UUID request,MoneroSignedPayment selected) {
        return jdbc.update("""
                update monero_transaction set tx_hash=?,selected_attempt_id=?,artifact_hash=?,fee_atomic=?,status='PREPARED',updated_at=now()
                 where tenant_id=? and request_id=? and status='SIGNING' and tx_hash is null
                """,selected.transactionHash(),selected.signSessionId(),selected.artifactHash(),new BigDecimal(selected.feeAtomic()),tenant,request);
    }
    public int markSubmitted(UUID tenant,UUID request) {
        return jdbc.update("update monero_transaction set status='SENT',updated_at=now() where tenant_id=? and request_id=? and status='PREPARED'",tenant,request);
    }
    public java.util.List<MoneroTransactionRecord> pending(int limit) {
        if(limit<1 || limit>100)throw new IllegalArgumentException("invalid Monero recovery limit");
        return jdbc.query("select "+COLUMNS+" from monero_transaction where status<>'CONFIRMED' order by updated_at,id limit ?",(rs,row)->map(rs),limit);
    }
    public void touchPending(UUID tenant,UUID request) {
        jdbc.update("update monero_transaction set updated_at=now() where tenant_id=? and request_id=? and status<>'CONFIRMED'",tenant,request);
    }
    public int confirm(UUID tenant,UUID request,String proof,long height,String hash,int confirmations) {
        return jdbc.update("""
                update monero_transaction set status='CONFIRMED',execution_receipt=?::jsonb,block_height=?,block_hash=?,confirmations=?,updated_at=now()
                 where tenant_id=? and request_id=? and status in ('PREPARED','SENT') and tx_hash is not null
                """,proof,height,hash,confirmations,tenant,request);
    }
    public Optional<BigDecimal> findConfirmedFeeAtomic(String chain,String hash) {
        return jdbc.queryForList("select fee_atomic from monero_transaction where chain=? and tx_hash=? and status='CONFIRMED'",BigDecimal.class,chain,hash).stream().findFirst();
    }
    private static MoneroTransactionRecord map(ResultSet rs) throws SQLException {
        var fee=rs.getBigDecimal("fee_atomic");var height=rs.getObject("block_height",Long.class);
        return new MoneroTransactionRecord(rs.getObject("tenant_id",UUID.class),rs.getObject("request_id",UUID.class),rs.getString("sender"),rs.getString("tx_hash"),
                rs.getObject("selected_attempt_id",UUID.class),rs.getString("artifact_hash"),fee==null?null:fee.toBigIntegerExact(),rs.getString("status"),height,
                rs.getString("block_hash"),rs.getInt("confirmations"),rs.getString("execution_receipt"));
    }
}
