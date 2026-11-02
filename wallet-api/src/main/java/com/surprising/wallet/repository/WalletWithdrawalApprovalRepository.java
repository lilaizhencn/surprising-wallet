package com.surprising.wallet.repository;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.model.custody.WithdrawalApprovalRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** wallet_withdrawal_approval only. Permanent original admission receipt, not a generic outbox. */
@Repository
public class WalletWithdrawalApprovalRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public WalletWithdrawalApprovalRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public void insert(UUID withdrawalId,long orderId,WithdrawalIntent intent) {
        jdbc.update("""
                insert into wallet_withdrawal_approval(tenant_id,withdrawal_id,order_id,approval_request_id,chain,network,business_reference,intent_hash,intent)
                values (?,?,?,?,?,?,?,?,?::jsonb)
                """,intent.tenantId(),withdrawalId,orderId,intent.id(),intent.chain(),intent.network(),intent.businessReference(),intent.hash(json),json.writeValueAsString(intent));
    }
    public Optional<WithdrawalApprovalRecord> find(UUID tenant,UUID withdrawal) {
        return jdbc.query("select * from wallet_withdrawal_approval where tenant_id=? and withdrawal_id=?",(r,n)->map(r),tenant,withdrawal).stream().findFirst();
    }
    public Optional<WithdrawalApprovalRecord> lock(UUID tenant,UUID withdrawal) {
        return jdbc.query("select * from wallet_withdrawal_approval where tenant_id=? and withdrawal_id=? for update",(r,n)->map(r),tenant,withdrawal).stream().findFirst();
    }
    public Optional<WithdrawalApprovalRecord> findByBusiness(UUID tenant,String chain,String reference) {
        return jdbc.query("select * from wallet_withdrawal_approval where tenant_id=? and chain=? and business_reference=?",(r,n)->map(r),tenant,chain,reference).stream().findFirst();
    }
    public List<Identity> awaiting(int limit) {
        return jdbc.query("""
                select tenant_id,withdrawal_id from wallet_withdrawal_approval where state in ('QUEUED','PENDING','APPROVED')
                    or (state in ('REJECTED','EXPIRED') and not terminal_cleanup_complete)
                order by last_checked_at nulls first,created_at,withdrawal_id limit ?
                """,(r,n)->new Identity(r.getObject("tenant_id",UUID.class),r.getObject("withdrawal_id",UUID.class)),Math.min(Math.max(limit,1),100));
    }
    public int record(UUID tenant,UUID withdrawal,String state,ApprovalRequest receipt) {
        return jdbc.update("""
                update wallet_withdrawal_approval set state=?,receipt=?::jsonb,last_checked_at=now(),updated_at=now()
                where tenant_id=? and withdrawal_id=? and state in ('QUEUED','PENDING','APPROVED')
                """,state,json.writeValueAsString(receipt),tenant,withdrawal);
    }
    public void checked(UUID tenant,UUID withdrawal) {
        jdbc.update("update wallet_withdrawal_approval set last_checked_at=now() where tenant_id=? and withdrawal_id=?",tenant,withdrawal);
    }
    public boolean terminalCleanupComplete(UUID tenant,UUID withdrawal) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select terminal_cleanup_complete from wallet_withdrawal_approval where tenant_id=? and withdrawal_id=?",Boolean.class,tenant,withdrawal));
    }
    public void completeTerminalCleanup(UUID tenant,UUID withdrawal) {
        if(jdbc.update("""
                update wallet_withdrawal_approval set terminal_cleanup_complete=true,last_checked_at=now(),updated_at=now()
                where tenant_id=? and withdrawal_id=? and state in ('REJECTED','EXPIRED') and receipt->>'signingStarted'='false'
                """,tenant,withdrawal)!=1)throw new IllegalStateException("terminal approval cleanup ownership differs");
    }
    private WithdrawalApprovalRecord map(ResultSet r) throws SQLException {
        var receipt=r.getString("receipt");
        return new WithdrawalApprovalRecord(r.getObject("tenant_id",UUID.class),r.getObject("withdrawal_id",UUID.class),r.getLong("order_id"),
                json.readValue(r.getString("intent"),WithdrawalIntent.class),r.getString("state"),receipt==null?null:json.readValue(receipt,ApprovalRequest.class));
    }
    public List<WithdrawalApprovalRecord> list(UUID tenant,String state,int limit,int offset) {
        return jdbc.query("select * from wallet_withdrawal_approval where tenant_id=? and (?='' or state=?) order by created_at desc,withdrawal_id desc limit ? offset ?",(r,n)->map(r),tenant,state,state,limit,offset);
    }
    public long count(UUID tenant,String state) {
        return jdbc.queryForObject("select count(*) from wallet_withdrawal_approval where tenant_id=? and (?='' or state=?)",Long.class,tenant,state,state);
    }
    public record Identity(UUID tenantId,UUID withdrawalId) { }
}
