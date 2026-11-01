package com.surprising.wallet.approval.repository;

import com.surprising.wallet.approval.model.ApprovalSigningTransaction;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.signing_transaction only; tenant admission lock serializes creation and fee reservations. */
@Repository
public class ApprovalSigningTransactionRepository {
    private final JdbcTemplate jdbc;
    public ApprovalSigningTransactionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<ApprovalSigningTransaction> find(UUID tenant,String chain,String network,String type,String reference) {
        return jdbc.query("select * from approval.signing_transaction where tenant_id=? and chain=? and network=? and business_type=? and business_reference=?",
                (r,n)->new ApprovalSigningTransaction(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("chain"),
                        r.getString("network"),r.getString("business_type"),r.getString("business_reference"),r.getString("spend_domain"),
                        r.getString("business_hash"),r.getTimestamp("created_at").toInstant()),tenant,chain,network,type,reference).stream().findFirst();
    }
    public void insert(ApprovalSigningTransaction value) {
        jdbc.update("""
                insert into approval.signing_transaction(id,tenant_id,chain,network,business_type,business_reference,spend_domain,business_hash,created_at)
                values (?,?,?,?,?,?,?,?,?)
                """,value.id(),value.tenantId(),value.chain(),value.network(),value.businessType(),value.businessReference(),value.spendDomain(),value.businessHash(),Timestamp.from(value.createdAt()));
    }
}
