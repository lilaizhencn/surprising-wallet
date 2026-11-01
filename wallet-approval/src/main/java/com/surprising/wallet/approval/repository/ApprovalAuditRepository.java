package com.surprising.wallet.approval.repository;

import java.util.UUID;
import java.util.List;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** approval.audit only; every decision and policy change requires durable audit in the same transaction. */
@Repository
public class ApprovalAuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ApprovalAuditRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public void insert(UUID tenant, String actorType, String actorId, String action, UUID entity, Object detail) {
        jdbc.update("insert into approval.audit(id,tenant_id,actor_type,actor_id,action,entity_id,detail) values (?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(),tenant,actorType,actorId,action,entity.toString(),json.writeValueAsString(detail));
    }
    public List<Entry> list(UUID tenant,String action,int limit,int offset) {
        return jdbc.query("select id,actor_type,actor_id,action,entity_id,detail,created_at from approval.audit where tenant_id=? and (?='' or action=?) order by created_at desc,id desc limit ? offset ?",
                (r,n)->new Entry(r.getObject("id",UUID.class),r.getString("actor_type"),r.getString("actor_id"),r.getString("action"),r.getString("entity_id"),json.readTree(r.getString("detail")),r.getTimestamp("created_at").toInstant()),tenant,action,action,limit,offset);
    }
    public long count(UUID tenant,String action) {
        return jdbc.queryForObject("select count(*) from approval.audit where tenant_id=? and (?='' or action=?)",Long.class,tenant,action,action);
    }
    public record Entry(UUID id,String actorType,String actorId,String action,String entityId,tools.jackson.databind.JsonNode detail,Instant createdAt) { }
}
