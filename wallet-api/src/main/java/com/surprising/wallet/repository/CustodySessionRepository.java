package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.SessionTokenRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** custody_session 单表仓储。 */
@Repository
public class CustodySessionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造会话单表仓储。 */
    public CustodySessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 撤销租户全部有效会话。 */
    public int revokeByTenant(UUID tenantId) {
        return jdbc.update("""
                update custody_session set revoked_at = now()
                 where tenant_id = ? and revoked_at is null
                """, tenantId);
    }

    public int revokeByUser(UUID tenantId, UUID userId) {
        return jdbc.update("update custody_session set revoked_at=now() where tenant_id=? and tenant_user_id=? and revoked_at is null",
                tenantId, userId);
    }

    /** 插入登录会话。 */
    public void insert(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                       String sourceIp, String userAgent, Instant expiresAt) {
        jdbc.update("""
                insert into custody_session(
                    id, tenant_user_id, tenant_id, token_hash, source_ip, user_agent, expires_at)
                values (?, ?, ?, ?, cast(nullif(?, '') as inet), ?, ?)
                """, sessionId, userId, tenantId, tokenHash, sourceIp,
                userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 512)),
                Timestamp.from(expiresAt));
    }

    /** 按令牌查询当前会话单表字段。 */
    public Optional<SessionTokenRecord> findActive(String tokenHash) {
        return jdbc.query("""
                select id, tenant_user_id, tenant_id, expires_at
                  from custody_session
                 where token_hash = ? and revoked_at is null and expires_at > now()
                """, (rs, rowNum) -> new SessionTokenRecord(rs.getObject("id", UUID.class),
                rs.getObject("tenant_user_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant()), tokenHash).stream().findFirst();
    }

    public void setVersion(UUID id, long version) {
        jdbc.update("update custody_session set security_version=? where id=?",version,id);
    }
    public void restrict(UUID id, long version, boolean email, boolean totp) {
        jdbc.update("update custody_session set verified=false,security_version=?,email_required=?,totp_required=?,expires_at=now()+interval '5 minutes' where id=?",version,email,totp,id);
    }

    public AuthState authState(String hash, boolean lock) {
        return jdbc.query("select * from custody_session where token_hash=? and revoked_at is null and expires_at>now()"+(lock ? " for update" : ""),
            (r,n) -> new AuthState(r.getObject("id",UUID.class),r.getObject("tenant_user_id",UUID.class),r.getObject("tenant_id",UUID.class),
                r.getBoolean("verified"),r.getLong("security_version"),r.getBoolean("email_required"),r.getBoolean("email_verified"),r.getBoolean("totp_required"),
                r.getInt("verification_attempts"),r.getString("email_code_hash"),instant(r.getTimestamp("email_code_expires_at")),instant(r.getTimestamp("email_sent_at")),
                r.getString("enrollment_ciphertext"),instant(r.getTimestamp("enrollment_expires_at")),r.getTimestamp("expires_at").toInstant()),hash)
            .stream().findFirst().orElseThrow(() -> new com.surprising.wallet.exception.CustodyUnauthorizedException("session expired or invalid"));
    }

    public boolean emailRecentlySent(UUID user) {
        return !jdbc.queryForList("select id from custody_session where tenant_user_id=? and email_sent_at>now()-interval '60 seconds' limit 1",UUID.class,user).isEmpty();
    }
    public void emailCode(UUID id, String hash) {
        jdbc.update("update custody_session set email_code_hash=?,email_code_expires_at=now()+interval '5 minutes',email_sent_at=now() where id=?",hash,id);
    }
    public void emailVerified(UUID id) {
        jdbc.update("update custody_session set email_verified=true,email_code_hash=null,email_code_expires_at=null where id=?",id);
    }
    public void failedVerification(UUID id) {
        jdbc.update("update custody_session set verification_attempts=verification_attempts+1,revoked_at=case when verification_attempts>=4 then now() else revoked_at end where id=?",id);
    }
    public void enrollment(UUID id, String ciphertext) {
        jdbc.update("update custody_session set enrollment_ciphertext=?,enrollment_expires_at=now()+interval '5 minutes' where id=?",ciphertext,id);
    }
    public void finish(UUID id, String hash, long version, Instant expiry) {
        jdbc.update("update custody_session set token_hash=?,verified=true,security_version=?,expires_at=?,email_code_hash=null,email_code_expires_at=null,enrollment_ciphertext=null,enrollment_expires_at=null where id=?",hash,version,Timestamp.from(expiry),id);
    }
    public void retainOnly(UUID user, UUID current, long version) {
        jdbc.update("update custody_session set revoked_at=now() where tenant_user_id=? and id<>? and revoked_at is null",user,current);
        jdbc.update("update custody_session set security_version=?,enrollment_ciphertext=null,enrollment_expires_at=null where id=?",version,current);
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    public record AuthState(UUID id,UUID userId,UUID tenantId,boolean verified,long version,boolean emailRequired,boolean emailVerified,
        boolean totpRequired,int attempts,String emailHash,Instant emailExpiry,Instant emailSent,String enrollment,Instant enrollmentExpiry,Instant expiresAt) { }

    /** 统计租户当前有效会话。 */
    public long countActive(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_session
                 where tenant_id = ? and revoked_at is null and expires_at > now()
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    /** 更新会话最近访问时间。 */
    public void touch(UUID sessionId) {
        jdbc.update("""
                update custody_session set last_seen_at = now()
                 where id = ? and last_seen_at < now() - interval '5 minutes'
                """, sessionId);
    }

    /** 按令牌撤销会话。 */
    public void revoke(String tokenHash) {
        jdbc.update("""
                update custody_session set revoked_at = now()
                 where token_hash = ? and revoked_at is null
                """, tokenHash);
    }

    /** 删除长期过期或已撤销的会话。 */
    public int deleteExpired() {
        return jdbc.update("""
                delete from custody_session
                 where expires_at < now() - interval '7 days'
                    or revoked_at < now() - interval '7 days'
                """);
    }
}
