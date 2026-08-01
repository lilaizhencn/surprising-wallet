package com.surprising.wallet.custody.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.surprising.wallet.custody.repository.CustodyRepository.ApiKeyRecord;
import static com.surprising.wallet.custody.repository.CustodyRepository.AuthUser;
import static com.surprising.wallet.custody.repository.CustodyRepository.SessionRecord;

/**
 * 托管身份、会话、API 密钥、重放保护和 IP 白名单仓储。
 */
@Repository
public class CustodySecurityRepository {
    /**
     * 保存 {@code jdbc}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbc;

    /**
     * 构造 {@code CustodySecurityRepository}，初始化该组件运行所需的状态和依赖。
     */
    public CustodySecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 删除或释放 {@code revokeTenantSessions} 对应的资源，并收敛相关业务状态。
     */
    public int revokeTenantSessions(UUID tenantId) {
        return jdbc.update("""
                update custody_session
                   set revoked_at = now()
                 where tenant_id = ? and revoked_at is null
                """, tenantId);
    }

    /**
     * 获取或查询 {@code findTenantUser} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AuthUser> findTenantUser(String email) {
        return jdbc.query("""
                        select u.id, u.tenant_id, t.slug as tenant_slug, t.status as tenant_status,
                               u.email, u.display_name, u.password_hash, u.role, u.status,
                               u.failed_login_count, u.locked_until
                          from custody_tenant_user u
                          join custody_tenant t on t.id = u.tenant_id
                         where u.tenant_id is not null and lower(u.email) = lower(?)
                        """, (rs, rowNum) -> mapAuthUser(rs), email).stream().findFirst();
    }

    /**
     * 获取或查询 {@code findPlatformUser} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AuthUser> findPlatformUser(String email) {
        return jdbc.query("""
                        select u.id, u.tenant_id, null::varchar as tenant_slug,
                               'ACTIVE'::varchar as tenant_status, u.email, u.display_name,
                               u.password_hash, u.role, u.status, u.failed_login_count, u.locked_until
                          from custody_tenant_user u
                         where u.tenant_id is null
                           and u.role = 'PLATFORM_ADMIN'
                           and lower(u.email) = lower(?)
                        """, (rs, rowNum) -> mapAuthUser(rs), email).stream().findFirst();
    }

    /**
     * 执行 {@code platformAdminExists} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean platformAdminExists() {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_tenant_user
                 where tenant_id is null and role = 'PLATFORM_ADMIN'
                """, Long.class);
        return count != null && count > 0;
    }

    /**
     * 记录或保存 {@code insertPlatformAdmin} 对应的数据，并遵守幂等和事务约束。
     */
    public void insertPlatformAdmin(UUID userId, String email, String passwordHash) {
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, null, ?, 'Platform administrator', ?, 'PLATFORM_ADMIN', 'ACTIVE')
                on conflict do nothing
                """, userId, email.toLowerCase(Locale.ROOT), passwordHash);
    }

    /**
     * 记录或保存 {@code recordLoginFailure} 对应的数据，并遵守幂等和事务约束。
     */
    public void recordLoginFailure(UUID userId, Instant lockedUntil) {
        jdbc.update("""
                        update custody_tenant_user
                           set failed_login_count = failed_login_count + 1,
                               locked_until = ?,
                               updated_at = now()
                         where id = ?
                        """, timestampOrNull(lockedUntil), userId);
    }

    /**
     * 记录或保存 {@code recordLoginSuccess} 对应的数据，并遵守幂等和事务约束。
     */
    public void recordLoginSuccess(UUID userId) {
        jdbc.update("""
                        update custody_tenant_user
                           set failed_login_count = 0, locked_until = null,
                               last_login_at = now(), updated_at = now()
                         where id = ?
                        """, userId);
    }

    /**
     * 记录或保存 {@code insertSession} 对应的数据，并遵守幂等和事务约束。
     */
    public void insertSession(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                              String sourceIp, String userAgent, Instant expiresAt) {
        jdbc.update("""
                        insert into custody_session(
                            id, tenant_user_id, tenant_id, token_hash, source_ip, user_agent, expires_at)
                        values (?, ?, ?, ?, cast(nullif(?, '') as inet), ?, ?)
                        """, sessionId, userId, tenantId, tokenHash, sourceIp, truncate(userAgent, 512),
                Timestamp.from(expiresAt));
    }

    /**
     * 获取或查询 {@code findActiveSession} 对应的数据，供调用方读取当前状态。
     */
    public Optional<SessionRecord> findActiveSession(String tokenHash) {
        return jdbc.query("""
                        select s.id as session_id, s.tenant_user_id, s.tenant_id, t.slug as tenant_slug,
                               u.email, u.display_name, u.role, u.status as user_status,
                               coalesce(t.status, 'ACTIVE') as tenant_status, s.expires_at
                          from custody_session s
                          join custody_tenant_user u on u.id = s.tenant_user_id
                          left join custody_tenant t on t.id = s.tenant_id
                         where s.token_hash = ?
                           and s.revoked_at is null
                           and s.expires_at > now()
                        """, (rs, rowNum) -> new SessionRecord(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("tenant_user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getString("user_status"),
                        rs.getString("tenant_status"),
                        rs.getTimestamp("expires_at").toInstant()),
                tokenHash).stream().findFirst();
    }

    /**
     * 获取或查询 {@code listTenantUsers} 对应的数据，供调用方读取当前状态。
     */
    public List<Map<String, Object>> listTenantUsers(UUID tenantId) {
        return jdbc.query("""
                        select id, email, display_name, role, status, failed_login_count,
                               locked_until, last_login_at, created_at, updated_at
                          from custody_tenant_user
                         where tenant_id = ?
                         order by case role
                                      when 'TENANT_ADMIN' then 0
                                      when 'OPERATOR' then 1
                                      else 2
                                  end,
                                  created_at,
                                  id
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("email", rs.getString("email"));
                    row.put("displayName", rs.getString("display_name"));
                    row.put("role", rs.getString("role"));
                    row.put("status", rs.getString("status"));
                    row.put("failedLoginCount", rs.getInt("failed_login_count"));
                    row.put("lockedUntil", instantOrNull(rs.getTimestamp("locked_until")));
                    row.put("lastLoginAt", instantOrNull(rs.getTimestamp("last_login_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId);
    }

    /**
     * 执行 {@code unlockTenantAdministrator} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Map<String, Object> unlockTenantAdministrator(UUID tenantId, UUID userId) {
        if (jdbc.update("""
                        update custody_tenant_user
                           set failed_login_count = 0, locked_until = null, updated_at = now()
                         where tenant_id = ? and id = ?
                           and role = 'TENANT_ADMIN' and status = 'ACTIVE'
                        """, tenantId, userId) != 1) {
            throw new IllegalArgumentException("active tenant administrator not found");
        }
        return listTenantUsers(tenantId).stream()
                .filter(user -> userId.equals(user.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "tenant administrator not found"));
    }

    /**
     * 编码 {@code touchSession} 对应的数据，生成链上或接口所需的表示。
     */
    public void touchSession(UUID sessionId) {
        jdbc.update("""
                        update custody_session
                           set last_seen_at = now()
                         where id = ?
                           and last_seen_at < now() - interval '5 minutes'
                        """, sessionId);
    }

    /**
     * 删除或释放 {@code revokeSession} 对应的资源，并收敛相关业务状态。
     */
    public void revokeSession(String tokenHash) {
        jdbc.update("""
                update custody_session set revoked_at = now()
                 where token_hash = ? and revoked_at is null
                """, tokenHash);
    }

    /**
     * 记录或保存 {@code insertApiKey} 对应的数据，并遵守幂等和事务约束。
     */
    public ApiKeyRecord insertApiKey(UUID id, UUID tenantId, String keyId, String name,
                                     String encryptedSecret, UUID createdBy) {
        jdbc.update("""
                        insert into custody_api_key(
                            id, tenant_id, key_id, name, secret_ciphertext, created_by)
                        values (?, ?, ?, ?, ?, ?)
                        """, id, tenantId, keyId, name, encryptedSecret, createdBy);
        return requireApiKey(keyId);
    }

    /**
     * 获取或查询 {@code findActiveApiKey} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ApiKeyRecord> findActiveApiKey(String keyId) {
        return jdbc.query("""
                        select k.id, k.tenant_id, t.slug as tenant_slug, t.status as tenant_status,
                               t.ip_allowlist_enabled, k.key_id, k.name, k.secret_ciphertext,
                               k.status, k.expires_at, k.created_at
                          from custody_api_key k
                          join custody_tenant t on t.id = k.tenant_id
                         where k.key_id = ?
                           and k.status = 'ACTIVE'
                           and (k.expires_at is null or k.expires_at > now())
                        """, (rs, rowNum) -> mapApiKey(rs), keyId).stream().findFirst();
    }

    /**
     * 校验 {@code requireApiKey} 对应的前置条件，不满足时抛出明确异常。
     */
    public ApiKeyRecord requireApiKey(String keyId) {
        return jdbc.query("""
                        select k.id, k.tenant_id, t.slug as tenant_slug, t.status as tenant_status,
                               t.ip_allowlist_enabled, k.key_id, k.name, k.secret_ciphertext,
                               k.status, k.expires_at, k.created_at
                          from custody_api_key k
                          join custody_tenant t on t.id = k.tenant_id
                         where k.key_id = ?
                        """, (rs, rowNum) -> mapApiKey(rs), keyId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("API key not found"));
    }

    /**
     * 获取或查询 {@code listApiKeys} 对应的数据，供调用方读取当前状态。
     */
    public List<Map<String, Object>> listApiKeys(UUID tenantId) {
        return jdbc.query("""
                        select id, key_id, name, status, last_used_at, last_used_ip,
                               expires_at, created_at, revoked_at
                          from custody_api_key
                         where tenant_id = ?
                         order by created_at desc
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("keyId", rs.getString("key_id"));
                    row.put("name", rs.getString("name"));
                    row.put("status", rs.getString("status"));
                    row.put("lastUsedAt", instantOrNull(rs.getTimestamp("last_used_at")));
                    row.put("lastUsedIp", rs.getString("last_used_ip"));
                    row.put("expiresAt", instantOrNull(rs.getTimestamp("expires_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("revokedAt", instantOrNull(rs.getTimestamp("revoked_at")));
                    return row;
                }, tenantId);
    }

    /**
     * 删除或释放 {@code revokeApiKey} 对应的资源，并收敛相关业务状态。
     */
    public void revokeApiKey(UUID tenantId, UUID keyId) {
        if (jdbc.update("""
                        update custody_api_key
                           set status = 'REVOKED', revoked_at = now()
                         where tenant_id = ? and id = ? and status = 'ACTIVE'
                        """, tenantId, keyId) != 1) {
            throw new IllegalArgumentException("active API key not found");
        }
    }

    /**
     * 编码 {@code touchApiKey} 对应的数据，生成链上或接口所需的表示。
     */
    public void touchApiKey(UUID keyId, String sourceIp) {
        jdbc.update("""
                        update custody_api_key
                           set last_used_at = now(), last_used_ip = cast(nullif(?, '') as inet)
                         where id = ?
                           and (
                               last_used_at is null
                               or last_used_at < now() - interval '1 minute'
                               or last_used_ip is distinct from cast(nullif(?, '') as inet)
                           )
                        """, sourceIp, keyId, sourceIp);
    }

    /**
     * 执行 {@code reserveNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean reserveNonce(String keyId, String nonce, Instant expiresAt) {
        try {
            return jdbc.update("""
                    insert into custody_api_nonce(key_id, nonce, expires_at)
                    values (?, ?, ?)
                    """, keyId, nonce, Timestamp.from(expiresAt)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 执行 {@code activeIpRules} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<String> activeIpRules(UUID tenantId) {
        return jdbc.query("""
                select cidr::text from custody_ip_rule
                 where tenant_id = ? and enabled = true
                 order by cidr
                """, (rs, rowNum) -> rs.getString(1), tenantId);
    }

    /**
     * 获取或查询 {@code listIpRules} 对应的数据，供调用方读取当前状态。
     */
    public List<Map<String, Object>> listIpRules(UUID tenantId) {
        return jdbc.query("""
                        select id, label, cidr::text as cidr, enabled, created_at, updated_at
                          from custody_ip_rule
                         where tenant_id = ?
                         order by created_at desc
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("label", rs.getString("label"));
                    row.put("cidr", rs.getString("cidr"));
                    row.put("enabled", rs.getBoolean("enabled"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId);
    }

    /**
     * 记录或保存 {@code insertIpRule} 对应的数据，并遵守幂等和事务约束。
     */
    public Map<String, Object> insertIpRule(
            UUID tenantId, UUID ruleId, String label, String cidr, UUID createdBy) {
        return jdbc.queryForMap("""
                        insert into custody_ip_rule(id, tenant_id, label, cidr, created_by)
                        values (?, ?, ?, cast(? as inet), ?)
                        returning id, label, cidr::text as cidr, enabled, created_at, updated_at
                        """, ruleId, tenantId, label, cidr, createdBy);
    }

    /**
     * 删除或清理 {@code deleteIpRule} 对应的数据，并处理相关状态收敛。
     */
    public void deleteIpRule(UUID tenantId, UUID ruleId) {
        if (jdbc.update(
                "delete from custody_ip_rule where tenant_id = ? and id = ?",
                tenantId, ruleId) != 1) {
            throw new IllegalArgumentException("IP rule not found");
        }
    }

    /**
     * 设置或更新 {@code setIpAllowlistEnabled} 对应的状态，并保持相关业务字段一致。
     */
    public void setIpAllowlistEnabled(UUID tenantId, boolean enabled) {
        if (enabled && activeIpRules(tenantId).isEmpty()) {
            throw new IllegalStateException(
                    "add at least one enabled IP rule before enforcing the allowlist");
        }
        if (jdbc.update("""
                update custody_tenant
                   set ip_allowlist_enabled = ?, updated_at = now()
                 where id = ?
                """, enabled, tenantId) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
    }

    /**
     * 执行 {@code mapAuthUser} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private AuthUser mapAuthUser(ResultSet rs) throws SQLException {
        return new AuthUser(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_slug"),
                rs.getString("tenant_status"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getInt("failed_login_count"),
                instantOrNull(rs.getTimestamp("locked_until")));
    }

    /**
     * 执行 {@code mapApiKey} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ApiKeyRecord mapApiKey(ResultSet rs) throws SQLException {
        return new ApiKeyRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_slug"),
                rs.getString("tenant_status"),
                rs.getBoolean("ip_allowlist_enabled"),
                rs.getString("key_id"),
                rs.getString("name"),
                rs.getString("secret_ciphertext"),
                rs.getString("status"),
                instantOrNull(rs.getTimestamp("expires_at")),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 转换或计算 {@code instantOrNull} 对应的值，统一金额、格式和边界规则。
     */
    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 执行 {@code timestampOrNull} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Timestamp timestampOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * 转换或计算 {@code truncate} 对应的值，统一金额、格式和边界规则。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
