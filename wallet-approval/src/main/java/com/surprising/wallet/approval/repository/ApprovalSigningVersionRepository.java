package com.surprising.wallet.approval.repository;

import com.surprising.wallet.approval.model.ApprovalSigningVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalSigningVersionRepository {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json;
    public ApprovalSigningVersionRepository(JdbcTemplate jdbc,tools.jackson.databind.ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public Optional<ApprovalSigningVersion> find(UUID tenant,UUID transaction,String hash) {
        return jdbc.query("select * from approval.signing_version where tenant_id=? and transaction_id=? and version_hash=?",
                (r,n)->new ApprovalSigningVersion(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("transaction_id",UUID.class),
                        r.getString("version_hash"),r.getString("payload_encoding"),r.getString("payload_hex"),r.getString("chain_evidence")==null?null:json.readTree(r.getString("chain_evidence"))),tenant,transaction,hash).stream().findFirst();
    }
    public void insert(ApprovalSigningVersion value) {
        jdbc.update("insert into approval.signing_version(id,tenant_id,transaction_id,version_hash,payload_encoding,payload_hex,chain_evidence) values (?,?,?,?,?,?,?::jsonb)",
                value.id(),value.tenantId(),value.transactionId(),value.versionHash(),value.payloadEncoding(),value.payloadHex(),value.chainEvidence()==null?null:json.writeValueAsString(value.chainEvidence()));
    }
    public java.util.List<ApprovalSigningVersion> history(UUID tenant,UUID transaction) {
        return jdbc.query("select * from approval.signing_version where tenant_id=? and transaction_id=? order by created_at,id limit 6",
                (r,n)->new ApprovalSigningVersion(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("transaction_id",UUID.class),
                        r.getString("version_hash"),r.getString("payload_encoding"),r.getString("payload_hex"),r.getString("chain_evidence")==null?null:json.readTree(r.getString("chain_evidence"))),tenant,transaction);
    }
}
