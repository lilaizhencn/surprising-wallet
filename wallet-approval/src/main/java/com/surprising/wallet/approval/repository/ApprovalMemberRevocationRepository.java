package com.surprising.wallet.approval.repository;

import com.surprising.wallet.common.approval.ApprovalMemberRevocation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** approval.member_revocation only. A missing reviewer does not discard a previously delivered revocation fence. */
@Repository
public class ApprovalMemberRevocationRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ApprovalMemberRevocationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<ApprovalMemberRevocation> find(UUID tenant,UUID member) {
        return jdbc.query("select descriptor from approval.member_revocation where tenant_id=? and member_id=?",
                (r,n)->json.readValue(r.getString("descriptor"),ApprovalMemberRevocation.class),tenant,member).stream().findFirst();
    }
    public void advance(ApprovalMemberRevocation event) {
        if(jdbc.update("""
                insert into approval.member_revocation(tenant_id,member_id,sequence,descriptor_hash,descriptor)
                values (?,?,?,?,?::jsonb) on conflict(tenant_id,member_id) do update
                set sequence=excluded.sequence,descriptor_hash=excluded.descriptor_hash,descriptor=excluded.descriptor
                where approval.member_revocation.sequence<excluded.sequence
                """,event.tenantId(),event.memberId(),event.sequence(),event.hash(),json.writeValueAsString(event))!=1)
            throw new IllegalStateException("independent revocation fence changed");
    }
    public java.util.Map<UUID,Long> sequences(UUID tenant,java.util.List<UUID> members) {
        if(members.isEmpty())return java.util.Map.of();
        if(members.size()>100)throw new IllegalArgumentException("bounded member page required");
        return jdbc.query("select member_id,sequence from approval.member_revocation where tenant_id=? and member_id=any(?)",r->{
            var result=new java.util.HashMap<UUID,Long>();while(r.next())result.put(r.getObject("member_id",UUID.class),r.getLong("sequence"));return result;
        },tenant,new org.springframework.jdbc.support.SqlArrayValue("uuid",members.toArray()));
    }
}
