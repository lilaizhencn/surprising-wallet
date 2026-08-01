package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_tenant_user 单表仓储。 */
@Repository
public class CustodyTenantUserRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造租户用户单表仓储。 */
    public CustodyTenantUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按邮箱查询租户用户。 */
    public List<Map<String, Object>> findByEmail(String email, boolean platformAdmin) {
        String condition = platformAdmin
                ? "tenant_id is null and role = 'PLATFORM_ADMIN'"
                : "tenant_id is not null";
        return jdbc.queryForList(
                "select id, tenant_id, email, display_name, password_hash, role, status, "
                        + "failed_login_count, locked_until from custody_tenant_user where "
                        + condition + " and lower(email) = lower(?)", email);
    }

    /** 按用户主键查询单表字段。 */
    public List<Map<String, Object>> findById(UUID userId) {
        return jdbc.queryForList("""
                select id, tenant_id, email, display_name, password_hash, role, status,
                       failed_login_count, locked_until
                  from custody_tenant_user
                 where id = ?
                """, userId);
    }

    /** 创建租户管理员用户。 */
    public void insertTenantAdmin(UUID userId, UUID tenantId, String email,
                                  String displayName, String passwordHash) {
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, ?, ?, 'TENANT_ADMIN', 'ACTIVE')
                """, userId, tenantId, email, displayName, passwordHash);
    }

    /** 判断平台管理员是否存在。 */
    public boolean platformAdminExists() {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_tenant_user
                 where tenant_id is null and role = 'PLATFORM_ADMIN'
                """, Long.class);
        return count != null && count > 0;
    }

    /** 插入平台管理员。 */
    public void insertPlatformAdmin(UUID userId, String email, String passwordHash) {
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, null, ?, 'Platform administrator', ?, 'PLATFORM_ADMIN', 'ACTIVE')
                on conflict do nothing
                """, userId, email.toLowerCase(java.util.Locale.ROOT), passwordHash);
    }

    /** 记录登录失败。 */
    public void recordLoginFailure(UUID userId, java.sql.Timestamp lockedUntil) {
        jdbc.update("""
                update custody_tenant_user
                   set failed_login_count = failed_login_count + 1,
                       locked_until = ?, updated_at = now()
                 where id = ?
                """, lockedUntil, userId);
    }

    /** 记录登录成功。 */
    public void recordLoginSuccess(UUID userId) {
        jdbc.update("""
                update custody_tenant_user
                   set failed_login_count = 0, locked_until = null,
                       last_login_at = now(), updated_at = now()
                 where id = ?
                """, userId);
    }

    /** 查询租户用户列表。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, email, display_name, role, status, failed_login_count,
                       locked_until, last_login_at, created_at, updated_at
                  from custody_tenant_user
                 where tenant_id = ?
                 order by case role when 'TENANT_ADMIN' then 0 when 'OPERATOR' then 1 else 2 end,
                          created_at, id
                """, tenantId);
    }

    /** 解锁租户管理员。 */
    public int unlockAdministrator(UUID tenantId, UUID userId) {
        return jdbc.update("""
                update custody_tenant_user
                   set failed_login_count = 0, locked_until = null, updated_at = now()
                 where tenant_id = ? and id = ?
                   and role = 'TENANT_ADMIN' and status = 'ACTIVE'
                """, tenantId, userId);
    }

    /** 将用户字段映射为登录模型所需的键值。 */
    public static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.stream().findFirst().orElse(null);
    }
}
