package com.surprising.wallet.repository;

import com.surprising.wallet.repository.CustodyRepository.ApiKeyRecord;
import com.surprising.wallet.repository.CustodyRepository.AuthUser;
import com.surprising.wallet.repository.CustodyRepository.SessionRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 托管安全数据访问门面，实际 SQL 由身份、会话、密钥等单表仓储执行。 */
@Component
public class CustodySecurityRepository {
    /** 租户用户单表仓储。 */
    private final CustodyTenantUserRepository users;
    /** 租户单表仓储。 */
    private final CustodyTenantTableRepository tenants;
    /** 会话单表仓储。 */
    private final CustodySessionRepository sessions;
    /** API 密钥单表仓储。 */
    private final CustodyApiKeyRepository apiKeys;
    /** API nonce 单表仓储。 */
    private final CustodyApiNonceRepository nonces;
    /** IP 规则单表仓储。 */
    private final CustodyIpRuleRepository ipRules;

    /** 兼容测试和手工构造。 */
    public CustodySecurityRepository(JdbcTemplate jdbc) {
        this(new CustodyTenantUserRepository(jdbc), new CustodyTenantTableRepository(jdbc),
                new CustodySessionRepository(jdbc), new CustodyApiKeyRepository(jdbc),
                new CustodyApiNonceRepository(jdbc), new CustodyIpRuleRepository(jdbc));
    }

    /** Spring 构造器，注入各自负责单表的数据访问组件。 */
    @Autowired
    public CustodySecurityRepository(
            CustodyTenantUserRepository users,
            CustodyTenantTableRepository tenants,
            CustodySessionRepository sessions,
            CustodyApiKeyRepository apiKeys,
            CustodyApiNonceRepository nonces,
            CustodyIpRuleRepository ipRules) {
        this.users = users;
        this.tenants = tenants;
        this.sessions = sessions;
        this.apiKeys = apiKeys;
        this.nonces = nonces;
        this.ipRules = ipRules;
    }

    /** 撤销租户全部会话。 */
    public int revokeTenantSessions(UUID tenantId) {
        return sessions.revokeByTenant(tenantId);
    }

    /** 查询租户用户并在 Java 中补充租户状态。 */
    public Optional<AuthUser> findTenantUser(String email) {
        Map<String, Object> user = first(users.findByEmail(email, false));
        if (user == null) {
            return Optional.empty();
        }
        Map<String, Object> tenant = tenants.findById(uuid(user.get("tenant_id")));
        if (tenant == null) {
            return Optional.empty();
        }
        return Optional.of(toAuthUser(user, tenant));
    }

    /** 查询平台管理员用户。 */
    public Optional<AuthUser> findPlatformUser(String email) {
        Map<String, Object> user = first(users.findByEmail(email, true));
        return user == null ? Optional.empty() : Optional.of(toAuthUser(user, null));
    }

    /** 判断平台管理员是否存在。 */
    public boolean platformAdminExists() {
        return users.platformAdminExists();
    }

    /** 插入平台管理员。 */
    public void insertPlatformAdmin(UUID userId, String email, String passwordHash) {
        users.insertPlatformAdmin(userId, email, passwordHash);
    }

    /** 记录登录失败。 */
    public void recordLoginFailure(UUID userId, Instant lockedUntil) {
        users.recordLoginFailure(userId, timestampOrNull(lockedUntil));
    }

    /** 记录登录成功。 */
    public void recordLoginSuccess(UUID userId) {
        users.recordLoginSuccess(userId);
    }

    /** 插入登录会话。 */
    public void insertSession(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                              String sourceIp, String userAgent, Instant expiresAt) {
        sessions.insert(sessionId, userId, tenantId, tokenHash, sourceIp, userAgent,
                Timestamp.from(expiresAt));
    }

    /** 查询会话并在 Java 中组合用户和租户信息。 */
    public Optional<SessionRecord> findActiveSession(String tokenHash) {
        Map<String, Object> session = first(sessions.findActive(tokenHash));
        if (session == null) {
            return Optional.empty();
        }
        Map<String, Object> user = first(users.findById(uuid(session.get("tenant_user_id"))));
        if (user == null) {
            return Optional.empty();
        }
        Map<String, Object> tenant = tenants.findById(uuid(session.get("tenant_id")));
        return Optional.of(new SessionRecord(
                uuid(session.get("id")), uuid(session.get("tenant_user_id")),
                uuid(session.get("tenant_id")), text(tenant, "slug"),
                text(user, "email"), text(user, "display_name"), text(user, "role"),
                text(user, "status"), tenant == null ? "ACTIVE" : text(tenant, "status"),
                instant(session.get("expires_at"))));
    }

    /** 查询租户用户列表。 */
    public List<Map<String, Object>> listTenantUsers(UUID tenantId) {
        return users.listByTenant(tenantId).stream()
                .map(CustodySecurityRepository::userView).toList();
    }

    /** 解锁租户管理员并返回更新后的用户。 */
    public Map<String, Object> unlockTenantAdministrator(UUID tenantId, UUID userId) {
        if (users.unlockAdministrator(tenantId, userId) != 1) {
            throw new IllegalArgumentException("active tenant administrator not found");
        }
        return listTenantUsers(tenantId).stream()
                .filter(row -> userId.equals(row.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tenant administrator not found"));
    }

    /** 更新会话最近访问时间。 */
    public void touchSession(UUID sessionId) {
        sessions.touch(sessionId);
    }

    /** 撤销会话。 */
    public void revokeSession(String tokenHash) {
        sessions.revoke(tokenHash);
    }

    /** 插入并读取 API 密钥。 */
    public ApiKeyRecord insertApiKey(UUID id, UUID tenantId, String keyId, String name,
                                     String encryptedSecret, UUID createdBy) {
        apiKeys.insert(id, tenantId, keyId, name, encryptedSecret, createdBy);
        return requireApiKey(keyId);
    }

    /** 查询有效 API 密钥。 */
    public Optional<ApiKeyRecord> findActiveApiKey(String keyId) {
        return firstApiKey(apiKeys.findActiveByKeyId(keyId));
    }

    /** 查询 API 密钥。 */
    public ApiKeyRecord requireApiKey(String keyId) {
        return firstApiKey(apiKeys.findByKeyId(keyId))
                .orElseThrow(() -> new IllegalArgumentException("API key not found"));
    }

    /** 查询租户 API 密钥列表。 */
    public List<Map<String, Object>> listApiKeys(UUID tenantId) {
        return apiKeys.listByTenant(tenantId).stream()
                .map(CustodySecurityRepository::apiKeyView).toList();
    }

    /** 撤销 API 密钥。 */
    public void revokeApiKey(UUID tenantId, UUID keyId) {
        if (apiKeys.revoke(tenantId, keyId) != 1) {
            throw new IllegalArgumentException("active API key not found");
        }
    }

    /** 更新 API 密钥最近使用信息。 */
    public void touchApiKey(UUID keyId, String sourceIp) {
        apiKeys.touch(keyId, sourceIp);
    }

    /** 预留 API nonce。 */
    public boolean reserveNonce(String keyId, String nonce, Instant expiresAt) {
        return nonces.reserve(keyId, nonce, Timestamp.from(expiresAt));
    }

    /** 清理安全域中的过期 nonce 和会话。 */
    public int cleanupExpiredRows() {
        return nonces.deleteExpired() + sessions.deleteExpired();
    }

    /** 统计租户有效会话。 */
    public long countActiveSessions(UUID tenantId) {
        return sessions.countActive(tenantId);
    }

    /** 统计租户有效 API 密钥。 */
    public long countActiveApiKeys(UUID tenantId) {
        return apiKeys.countActive(tenantId);
    }

    /** 查询启用的 IP 规则。 */
    public List<String> activeIpRules(UUID tenantId) {
        return ipRules.listActive(tenantId);
    }

    /** 查询租户 IP 规则。 */
    public List<Map<String, Object>> listIpRules(UUID tenantId) {
        return ipRules.list(tenantId);
    }

    /** 插入 IP 规则。 */
    public Map<String, Object> insertIpRule(UUID tenantId, UUID ruleId, String label,
                                            String cidr, UUID createdBy) {
        return ipRules.insert(tenantId, ruleId, label, cidr, createdBy);
    }

    /** 删除 IP 规则。 */
    public void deleteIpRule(UUID tenantId, UUID ruleId) {
        if (ipRules.delete(tenantId, ruleId) != 1) {
            throw new IllegalArgumentException("IP rule not found");
        }
    }

    /** 更新租户 IP 白名单开关。 */
    public void setIpAllowlistEnabled(UUID tenantId, boolean enabled) {
        if (enabled && activeIpRules(tenantId).isEmpty()) {
            throw new IllegalStateException(
                    "add at least one enabled IP rule before enforcing the allowlist");
        }
        if (tenants.updateIpAllowlist(tenantId, enabled) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
    }

    /** 将原始用户字段转换为认证模型。 */
    private static AuthUser toAuthUser(Map<String, Object> user, Map<String, Object> tenant) {
        return new AuthUser(uuid(user.get("id")), uuid(user.get("tenant_id")),
                text(tenant, "slug"), tenant == null ? "ACTIVE" : text(tenant, "status"),
                text(user, "email"), text(user, "display_name"), text(user, "password_hash"),
                text(user, "role"), text(user, "status"), intValue(user.get("failed_login_count")),
                instant(user.get("locked_until")));
    }

    /** 将 API 密钥字段和租户字段转换为认证模型。 */
    private ApiKeyRecord toApiKey(Map<String, Object> key) {
        Map<String, Object> tenant = tenants.findById(uuid(key.get("tenant_id")));
        return new ApiKeyRecord(uuid(key.get("id")), uuid(key.get("tenant_id")),
                text(tenant, "slug"), text(tenant, "status"),
                tenant != null && Boolean.TRUE.equals(tenant.get("ip_allowlist_enabled")),
                text(key, "key_id"), text(key, "name"), text(key, "secret_ciphertext"),
                text(key, "status"), instant(key.get("expires_at")), instant(key.get("created_at")));
    }

    /** 将 API 密钥列表转换为接口字段。 */
    private static Map<String, Object> apiKeyView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", uuid(row.get("id")));
        view.put("keyId", text(row, "key_id"));
        view.put("name", text(row, "name"));
        view.put("status", text(row, "status"));
        view.put("lastUsedAt", instant(row.get("last_used_at")));
        view.put("lastUsedIp", row.get("last_used_ip"));
        view.put("expiresAt", instant(row.get("expires_at")));
        view.put("createdAt", instant(row.get("created_at")));
        view.put("revokedAt", instant(row.get("revoked_at")));
        return view;
    }

    /** 将用户列表转换为接口字段。 */
    private static Map<String, Object> userView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", uuid(row.get("id")));
        view.put("email", text(row, "email"));
        view.put("displayName", text(row, "display_name"));
        view.put("role", text(row, "role"));
        view.put("status", text(row, "status"));
        view.put("failedLoginCount", intValue(row.get("failed_login_count")));
        view.put("lockedUntil", instant(row.get("locked_until")));
        view.put("lastLoginAt", instant(row.get("last_login_at")));
        view.put("createdAt", instant(row.get("created_at")));
        view.put("updatedAt", instant(row.get("updated_at")));
        return view;
    }

    /** 将密钥单表字段转换为记录。 */
    private Optional<ApiKeyRecord> firstApiKey(List<Map<String, Object>> rows) {
        return rows.stream().findFirst().map(this::toApiKey);
    }

    /** 取列表第一项。 */
    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.stream().findFirst().orElse(null);
    }

    /** 读取 Map 文本字段。 */
    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }

    /** 读取 UUID 字段。 */
    private static UUID uuid(Object value) {
        return value instanceof UUID result ? result : value == null ? null : UUID.fromString(value.toString());
    }

    /** 读取整数。 */
    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    /** 读取时间。 */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value instanceof Instant result ? result : null;
    }

    /** 转换可空时间。 */
    private static Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
