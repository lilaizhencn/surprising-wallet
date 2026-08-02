package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
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

    /** 插入登录会话。 */
    public void insert(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                       String sourceIp, String userAgent, Timestamp expiresAt) {
        jdbc.update("""
                insert into custody_session(
                    id, tenant_user_id, tenant_id, token_hash, source_ip, user_agent, expires_at)
                values (?, ?, ?, ?, cast(nullif(?, '') as inet), ?, ?)
                """, sessionId, userId, tenantId, tokenHash, sourceIp,
                userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 512)),
                expiresAt);
    }

    /** 按令牌查询当前会话单表字段。 */
    public List<Map<String, Object>> findActive(String tokenHash) {
        return jdbc.queryForList("""
                select id, tenant_user_id, tenant_id, expires_at
                  from custody_session
                 where token_hash = ? and revoked_at is null and expires_at > now()
                """, tokenHash);
    }

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
