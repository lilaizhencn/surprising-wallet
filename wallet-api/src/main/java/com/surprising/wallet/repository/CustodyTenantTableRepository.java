package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_tenant 单表仓储。 */
@Repository
public class CustodyTenantTableRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造租户单表仓储。 */
    public CustodyTenantTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询租户单表字段。 */
    public Map<String, Object> findById(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, slug, status, ip_allowlist_enabled
                  from custody_tenant
                 where id = ?
                """, tenantId);
        return rows.stream().findFirst().orElse(null);
    }

    /** 按 slug 查询租户完整字段。 */
    public Map<String, Object> findBySlug(String slug) {
        return jdbc.queryForList("""
                select id, slug, name, status, derivation_namespace, ip_allowlist_enabled,
                       display_currency, created_at, updated_at
                  from custody_tenant where slug = ?
                """, slug).stream().findFirst().orElse(null);
    }

    /** 查询租户完整字段。 */
    public Map<String, Object> findFullById(UUID tenantId) {
        return jdbc.queryForList("""
                select id, slug, name, status, derivation_namespace, ip_allowlist_enabled,
                       display_currency, created_at, updated_at
                  from custody_tenant where id = ?
                """, tenantId).stream().findFirst().orElse(null);
    }

    /** 创建租户记录。 */
    public void insert(UUID tenantId, String slug, String name) {
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, ?)",
                tenantId, slug, name);
    }

    /** 更新租户名称和显示币种。 */
    public int updateProfile(UUID tenantId, String name, String displayCurrency) {
        return jdbc.update("""
                update custody_tenant
                   set name = ?, display_currency = ?, updated_at = now()
                 where id = ?
                """, name, displayCurrency, tenantId);
    }

    /** 更新租户状态。 */
    public int updateStatus(UUID tenantId, String status) {
        return jdbc.update("""
                update custody_tenant set status = ?, updated_at = now() where id = ?
                """, status, tenantId);
    }

    /** 查询所有处于启用状态的租户主键。 */
    public List<UUID> listActiveIds() {
        return jdbc.queryForList("""
                select id from custody_tenant where status = 'ACTIVE'
                """, UUID.class);
    }

    /** 按租户编号更新 IP 白名单开关。 */
    public int updateIpAllowlist(UUID tenantId, boolean enabled) {
        return jdbc.update("""
                update custody_tenant
                   set ip_allowlist_enabled = ?, updated_at = now()
                 where id = ?
                """, enabled, tenantId);
    }
}
