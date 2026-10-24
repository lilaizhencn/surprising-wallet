package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.TenantRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** custody_tenant 单表仓储。 */
@Repository
public class CustodyTenantTableRepository {
    private static final String COLUMNS = "id, slug, name, status, derivation_namespace, "
            + "ip_allowlist_enabled, display_currency, created_at, updated_at";
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造租户单表仓储。 */
    public CustodyTenantTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantRecord> findById(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from custody_tenant where id = ?",
                CustodyTenantTableRepository::mapTenant, tenantId).stream().findFirst();
    }

    public Optional<TenantRecord> findBySlug(String slug) {
        return jdbc.query("select " + COLUMNS + " from custody_tenant where slug = ?",
                CustodyTenantTableRepository::mapTenant, slug).stream().findFirst();
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

    public List<TenantRecord> listAll() {
        return jdbc.query("select " + COLUMNS + " from custody_tenant order by created_at desc, id",
                CustodyTenantTableRepository::mapTenant);
    }

    /** 统计租户数量。 */
    public long count(String search, String status) {
        String value = search == null ? "" : search.trim();
        Long count = jdbc.queryForObject("""
                select count(*) from custody_tenant
                 where (? = '' or slug ilike '%' || ? || '%' or name ilike '%' || ? || '%')
                   and (? = '' or status = ?)
                """, Long.class, value, value, value, status == null ? "" : status, status == null ? "" : status);
        return count == null ? 0 : count;
    }

    /** 按租户编号更新 IP 白名单开关。 */
    public int updateIpAllowlist(UUID tenantId, boolean enabled) {
        return jdbc.update("""
                update custody_tenant
                   set ip_allowlist_enabled = ?, updated_at = now()
                 where id = ?
                """, enabled, tenantId);
    }
    private static TenantRecord mapTenant(ResultSet rs, int rowNum) throws SQLException {
        return new TenantRecord(rs.getObject("id", UUID.class), rs.getString("slug"),
                rs.getString("name"), rs.getString("status"), rs.getInt("derivation_namespace"),
                rs.getBoolean("ip_allowlist_enabled"), rs.getString("display_currency"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
