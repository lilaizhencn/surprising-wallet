package com.surprising.wallet.approval.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.member only; wallet user administration cannot create independent approval identities. */
@Repository
public class ApprovalMemberRepository {
    private final JdbcTemplate jdbc;
    public ApprovalMemberRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(UUID tenant,UUID id,String email,String name,String hash,String role) {
        jdbc.update("insert into approval.member(id,tenant_id,email,display_name,password_hash,role) values (?,?,?,?,?,?)",
                id,tenant,email,name,hash,role);
    }
    public void inviteAdministrator(UUID tenant,UUID id,String email,String name,String unusableHash,String tokenHash,Instant expires) {
        jdbc.update("insert into approval.member(id,tenant_id,email,display_name,password_hash,role,status,activation_token_hash,activation_expires_at) values (?,?,?,?,?,'TENANT_ADMIN','INVITED',?,?)",
                id,tenant,email,name,unusableHash,tokenHash,Timestamp.from(expires));
    }
    public Optional<Member> active(UUID tenant, UUID member) {
        return jdbc.query("select id,tenant_id,role from approval.member where tenant_id=? and id=? and status='ACTIVE'",
                (r,n) -> new Member(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("role")), tenant,member).stream().findFirst();
    }
    /** SHARE conflicts with credential/status changes but does not serialize unrelated tenant admissions. */
    public Optional<Member> activeForDecision(UUID tenant,UUID member) {
        return jdbc.query("select id,tenant_id,role from approval.member where tenant_id=? and id=? and status='ACTIVE' for share",
                (r,n)->new Member(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("role")),tenant,member).stream().findFirst();
    }
    public List<ManagedMember> list(UUID tenant,int limit,int offset) {
        return jdbc.query("select id,email,display_name,role,status,created_at,updated_at from approval.member where tenant_id=? order by created_at,id limit ? offset ?",
                (r,n)->managed(r),tenant,limit,offset);
    }
    public long count(UUID tenant) { return jdbc.queryForObject("select count(*) from approval.member where tenant_id=?",Long.class,tenant); }
    public Optional<ManagedMember> lock(UUID tenant,UUID id) {
        return jdbc.query("select id,email,display_name,role,status,created_at,updated_at from approval.member where tenant_id=? and id=? for update",
                (r,n)->managed(r),tenant,id).stream().findFirst();
    }
    public void invite(UUID tenant,UUID id,String email,String name,String unusableHash,String tokenHash,Instant expires) {
        jdbc.update("insert into approval.member(id,tenant_id,email,display_name,password_hash,role,status,activation_token_hash,activation_expires_at) values (?,?,?,?,?,'APPROVER','INVITED',?,?)",
                id,tenant,email,name,unusableHash,tokenHash,Timestamp.from(expires));
    }
    public void rotateActivation(UUID tenant,UUID id,String hash,Instant expires) {
        jdbc.update("update approval.member set status=case when status='INVITED' then 'INVITED' else 'RESET_REQUIRED' end,activation_token_hash=?,activation_expires_at=?,failed_attempts=0,locked_until=null,updated_at=now() where tenant_id=? and id=?",
                hash,Timestamp.from(expires),tenant,id);
    }
    public void disable(UUID tenant,UUID id) {
        jdbc.update("update approval.member set status='DISABLED',activation_token_hash=null,activation_expires_at=null,failed_attempts=0,locked_until=null,updated_at=now() where tenant_id=? and id=?",tenant,id);
    }
    public Optional<Member> activationIdentity(String hash) {
        return jdbc.query("select id,tenant_id,role from approval.member where activation_token_hash=?",(r,n)->new Member(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("role")),hash).stream().findFirst();
    }
    public int activate(UUID tenant,UUID id,String tokenHash,String passwordHash,Instant now) {
        return jdbc.update("update approval.member set password_hash=?,status='ACTIVE',activation_token_hash=null,activation_expires_at=null,failed_attempts=0,locked_until=null,updated_at=now() where tenant_id=? and id=? and activation_token_hash=? and activation_expires_at>? and status in ('INVITED','RESET_REQUIRED')",
                passwordHash,tenant,id,tokenHash,Timestamp.from(now));
    }
    private static ManagedMember managed(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ManagedMember(row.getObject("id",UUID.class),row.getString("email"),row.getString("display_name"),row.getString("role"),row.getString("status"),
                row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant());
    }
    public Optional<Credential> credentialByEmail(String email) {
        return jdbc.query("select id,tenant_id,role,email,display_name,password_hash,status,failed_attempts,locked_until from approval.member where lower(email)=? for update",
                (r,n)->credential(r),email).stream().findFirst();
    }
    public Optional<Credential> credentialByIdentity(UUID tenant,UUID member) {
        if(tenant==null || member==null)return Optional.empty();
        return jdbc.query("select id,tenant_id,role,email,display_name,password_hash,status,failed_attempts,locked_until from approval.member where tenant_id=? and id=? for update",
                (r,n)->credential(r),tenant,member).stream().findFirst();
    }
    private static Credential credential(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Credential(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("role"),r.getString("email"),
                r.getString("display_name"),r.getString("password_hash"),r.getString("status"),r.getInt("failed_attempts"),
                r.getTimestamp("locked_until")==null?null:r.getTimestamp("locked_until").toInstant());
    }
    public Optional<Profile> profile(UUID tenant,UUID member) {
        return jdbc.query("select id,role,email,display_name from approval.member where tenant_id=? and id=? and status='ACTIVE'",
                (r,n)->new Profile(r.getObject("id",UUID.class),r.getString("role"),r.getString("email"),r.getString("display_name")),tenant,member).stream().findFirst();
    }
    public void failed(UUID tenant,UUID member,int attempts,java.time.Instant lockedUntil) {
        jdbc.update("update approval.member set failed_attempts=?,locked_until=? where tenant_id=? and id=?",attempts,
                lockedUntil==null?null:java.sql.Timestamp.from(lockedUntil),tenant,member);
    }
    public record Credential(UUID id,UUID tenantId,String role,String email,String displayName,String passwordHash,String status,
            int failedAttempts,java.time.Instant lockedUntil) { }
    public record Profile(UUID id,String role,String email,String displayName) { }
    public record Member(UUID id, UUID tenantId, String role) { }
    public record ManagedMember(UUID id,String email,String displayName,String role,String status,Instant createdAt,Instant updatedAt) { }
}
