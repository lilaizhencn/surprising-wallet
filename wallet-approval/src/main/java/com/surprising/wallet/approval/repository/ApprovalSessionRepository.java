package com.surprising.wallet.approval.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.session only. Tokens are stored as SHA-256, never reusable Wallet sessions. */
@Repository
public class ApprovalSessionRepository {
    private final JdbcTemplate jdbc;
    public ApprovalSessionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void insert(UUID tenant,UUID member,String tokenHash,String csrf,Instant expires) {
        jdbc.update("insert into approval.session(id,tenant_id,member_id,token_hash,csrf_token,expires_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(),tenant,member,tokenHash,csrf,Timestamp.from(expires));
    }
    public Optional<Session> findActive(String hash,Instant now) {
        return jdbc.query("select tenant_id,member_id,csrf_token,expires_at from approval.session where token_hash=? and revoked_at is null and expires_at>?",
                (r,n)->new Session(r.getObject("tenant_id",UUID.class),r.getObject("member_id",UUID.class),r.getString("csrf_token"),r.getTimestamp("expires_at").toInstant()),
                hash,Timestamp.from(now)).stream().findFirst();
    }
    public void revoke(String hash) { jdbc.update("update approval.session set revoked_at=now() where token_hash=? and revoked_at is null",hash); }
    public void revokeMember(UUID tenant,UUID member) {
        jdbc.update("update approval.session set revoked_at=now() where tenant_id=? and member_id=? and revoked_at is null",tenant,member);
    }
    public record Session(UUID tenantId,UUID memberId,String csrfToken,Instant expiresAt) { }
}
