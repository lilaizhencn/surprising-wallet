package com.surprising.wallet.approval.repository;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** approval.request only. Sums are serialized by the tenant row in the owning service. */
@Repository
public class ApprovalRequestRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ApprovalRequestRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public Optional<ApprovalRequest> find(UUID tenant,UUID id) {
        return jdbc.query("select * from approval.request where tenant_id=? and id=?",(r,n)->map(r),tenant,id).stream().findFirst();
    }
    public Optional<ApprovalRequest> fundingParent(UUID tenant,String chain,com.surprising.wallet.common.approval.AutomaticParentReference.Parts reference) {
        var rows=jdbc.query("""
                select * from approval.request where tenant_id=? and chain=?
                and encode(sha256(convert_to(? || business_reference,'UTF8')),'hex')=? limit 2
                """,(r,n)->map(r),tenant,chain,reference.hashPrefix()+"WITHDRAWAL:",reference.parentHash());
        if(rows.size()>1)throw new IllegalStateException("ambiguous independently approved funding parent");return rows.stream().findFirst();
    }
    public Optional<ApprovalRequest> lock(UUID tenant,UUID id) {
        return jdbc.query("select * from approval.request where tenant_id=? and id=? for update",(r,n)->map(r),tenant,id).stream().findFirst();
    }
    public void insert(ApprovalRequest request) {
        var i=request.intent();
        jdbc.update("""
                insert into approval.request(id,tenant_id,business_reference,intent_hash,intent,asset_snapshot,chain,network,asset_symbol,
                    recipient,amount,source,initiator_id,policy_id,policy_version,reasons,required_reviewers,status,created_at,expires_at)
                values (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """,i.id(),i.tenantId(),i.businessReference(),request.intentHash(),json.writeValueAsString(i),json.writeValueAsString(request.assetSnapshot()),i.chain(),i.network(),
                i.assetSymbol(),i.recipient(),i.amount(),i.source(),i.initiatorId(),request.policyId(),request.policyVersion(),
                json.writeValueAsString(request.reasons()),request.requiredReviewers(),request.status(),
                Timestamp.from(request.createdAt()),Timestamp.from(request.expiresAt()));
    }
    public BigDecimal rollingAmount(UUID tenant,String chain,String network,String asset,Instant now) {
        return jdbc.queryForObject("""
                select coalesce(sum(amount),0) from approval.request where tenant_id=? and chain=? and network=? and asset_symbol=?
                and created_at>=? and status in ('PENDING','APPROVED') and (signing_transaction_id is not null or expires_at>?)
                """,BigDecimal.class,tenant,chain,network,asset,Timestamp.from(now.minusSeconds(86400)),Timestamp.from(now));
    }
    public boolean knownRecipient(UUID tenant,String chain,String network,String asset,String recipient,Instant now) {
        return !jdbc.queryForList("""
                select id from approval.request where tenant_id=? and chain=? and network=? and asset_symbol=? and recipient=?
                and status='APPROVED' and required_reviewers>0 and (signing_transaction_id is not null or expires_at>?) limit 1
                """,UUID.class,tenant,chain,network,asset,recipient,Timestamp.from(now)).isEmpty();
    }
    public int decide(UUID tenant,UUID id,String state) {
        if (!List.of("APPROVED","REJECTED","EXPIRED").contains(state)) throw new IllegalArgumentException("invalid decision state");
        return jdbc.update("update approval.request set status=? where tenant_id=? and id=? and status='PENDING' and signing_transaction_id is null",state,tenant,id);
    }
    public int expireApproved(UUID tenant,UUID id) {
        return jdbc.update("update approval.request set status='EXPIRED' where tenant_id=? and id=? and status='APPROVED' and signing_transaction_id is null",tenant,id);
    }
    public int bindSigningTransaction(UUID tenant,UUID id,UUID transaction) {
        return jdbc.update("update approval.request set signing_transaction_id=? where tenant_id=? and id=? and status='APPROVED' and signing_transaction_id is null",transaction,tenant,id);
    }
    public Optional<UUID> signingTransaction(UUID tenant,UUID id) {
        return jdbc.query("select signing_transaction_id from approval.request where tenant_id=? and id=? and signing_transaction_id is not null",
                (r,n)->r.getObject("signing_transaction_id",UUID.class),tenant,id).stream().findFirst();
    }
    public List<ApprovalRequest> list(UUID tenant,String state,int limit,int offset) {
        return jdbc.query("select * from approval.request where tenant_id=? and (?='' or status=?) order by created_at desc,id desc limit ? offset ?",
                (r,n)->map(r),tenant,state,state,Math.min(Math.max(limit,1),100),Math.max(offset,0));
    }
    public long count(UUID tenant,String state) {
        return jdbc.queryForObject("select count(*) from approval.request where tenant_id=? and (?='' or status=?)",Long.class,tenant,state,state);
    }
    public List<Identity> expired(Instant now,int limit) {
        return jdbc.query("select tenant_id,id from approval.request where status in ('PENDING','APPROVED') and signing_transaction_id is null and expires_at<=? order by expires_at,id limit ?",
                (r,n)->new Identity(r.getObject("tenant_id",UUID.class),r.getObject("id",UUID.class)),Timestamp.from(now),Math.min(Math.max(limit,1),500));
    }
    public record Identity(UUID tenantId,UUID requestId) { }
    private ApprovalRequest map(ResultSet r) throws SQLException {
        return new ApprovalRequest(json.readValue(r.getString("intent"),WithdrawalIntent.class),r.getString("intent_hash"),json.readValue(r.getString("asset_snapshot"),com.surprising.wallet.common.approval.ApprovalAssetSnapshot.class),
                r.getObject("policy_id",UUID.class),r.getLong("policy_version"),
                json.readValue(r.getString("reasons"),new TypeReference<List<String>>() {}),r.getInt("required_reviewers"),r.getString("status"),
                r.getObject("signing_transaction_id")!=null,r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant());
    }
}
