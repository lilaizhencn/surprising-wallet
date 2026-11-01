package com.surprising.wallet.approval.repository;

import com.surprising.wallet.common.approval.SigningGrant;
import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.approval.model.StoredSigningGrant;
import com.surprising.wallet.common.signing.MpcClient;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ApprovalSigningGrantRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ApprovalSigningGrantRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public Optional<StoredSigningGrant> find(UUID tenant,UUID slot,UUID session) {
        return jdbc.query("select * from approval.signing_grant where tenant_id=? and slot_id=? and session_id=?",
                (r,n)->new StoredSigningGrant(new SigningGrant(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("slot_id",UUID.class),
                        r.getObject("session_id",UUID.class),json.readValue(r.getString("proof"),SigningAuthorization.Proof.class),
                        r.getTimestamp("issued_at").toInstant(),r.getTimestamp("expires_at").toInstant()),json.readValue(r.getString("execution"),MpcClient.Request.class),
                        r.getString("replay_actor"),r.getString("replay_reason")),tenant,slot,session).stream().findFirst();
    }
    public void insert(StoredSigningGrant value) {
        var grant=value.grant();
        jdbc.update("insert into approval.signing_grant(id,tenant_id,slot_id,session_id,proof,execution,replay_actor,replay_reason,issued_at,expires_at) values (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)",
                grant.id(),grant.tenantId(),grant.slotId(),grant.sessionId(),json.writeValueAsString(grant.proof()),json.writeValueAsString(value.execution()),value.replayActor(),value.replayReason(),Timestamp.from(grant.issuedAt()),Timestamp.from(grant.expiresAt()));
    }
}
