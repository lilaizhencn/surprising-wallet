package com.surprising.wallet.approval.repository;

import com.surprising.wallet.approval.model.ApprovalSigningSlot;
import com.surprising.wallet.common.approval.SigningCandidate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ApprovalSigningSlotRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ApprovalSigningSlotRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public Optional<ApprovalSigningSlot> find(UUID tenant,UUID slot) {
        return jdbc.query("select * from approval.signing_slot where tenant_id=? and id=?",
                (r,n)->new ApprovalSigningSlot(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("version_id",UUID.class),
                        r.getString("candidate_hash"),json.readValue(r.getString("candidate"),SigningCandidate.class)),tenant,slot).stream().findFirst();
    }
    public void insert(ApprovalSigningSlot value) {
        jdbc.update("insert into approval.signing_slot(id,tenant_id,version_id,slot_name,candidate_hash,candidate) values (?,?,?,?,?,?::jsonb)",
                value.id(),value.tenantId(),value.versionId(),value.candidate().command().signingSlot(),value.candidateHash(),json.writeValueAsString(value.candidate()));
    }
    public boolean nameExists(UUID tenant,UUID version,String name) {
        return !jdbc.queryForList("select id from approval.signing_slot where tenant_id=? and version_id=? and slot_name=?",UUID.class,tenant,version,name).isEmpty();
    }
}
