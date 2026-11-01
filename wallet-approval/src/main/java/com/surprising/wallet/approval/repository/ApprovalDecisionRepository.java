package com.surprising.wallet.approval.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.decision only; each independent reviewer has one immutable decision per request. */
@Repository
public class ApprovalDecisionRepository {
    private final JdbcTemplate jdbc;
    public ApprovalDecisionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Decision> find(UUID tenant,UUID request,UUID reviewer) {
        return jdbc.query("select decision,reason from approval.decision where tenant_id=? and request_id=? and reviewer_id=?",
                (r,n)->new Decision(r.getString("decision"),r.getString("reason")),tenant,request,reviewer).stream().findFirst();
    }
    public void insert(UUID tenant,UUID request,UUID reviewer,String decision,String reason,Instant now) {
        jdbc.update("insert into approval.decision(id,tenant_id,request_id,reviewer_id,decision,reason,created_at) values (?,?,?,?,?,?,?)",
                UUID.randomUUID(),tenant,request,reviewer,decision,reason,java.sql.Timestamp.from(now));
    }
    public long approvals(UUID tenant,UUID request) {
        return jdbc.queryForObject("select count(*) from approval.decision where tenant_id=? and request_id=? and decision='APPROVE'",Long.class,tenant,request);
    }
    public record Decision(String decision,String reason) { }
}
