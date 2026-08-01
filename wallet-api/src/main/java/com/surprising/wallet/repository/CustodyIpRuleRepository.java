package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_ip_rule 单表仓储。 */
@Repository
public class CustodyIpRuleRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 IP 规则单表仓储。 */
    public CustodyIpRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询启用的 CIDR 文本。 */
    public List<String> listActive(UUID tenantId) {
        return jdbc.query("""
                select cidr::text from custody_ip_rule
                 where tenant_id = ? and enabled = true order by cidr
                """, (rs, rowNum) -> rs.getString(1), tenantId);
    }

    /** 查询租户 IP 规则。 */
    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.query("""
                select id, label, cidr::text as cidr, enabled, created_at, updated_at
                  from custody_ip_rule
                 where tenant_id = ? order by created_at desc
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

    /** 插入 IP 规则并返回展示字段。 */
    public Map<String, Object> insert(UUID tenantId, UUID ruleId, String label,
                                      String cidr, UUID createdBy) {
        return jdbc.queryForMap("""
                insert into custody_ip_rule(id, tenant_id, label, cidr, created_by)
                values (?, ?, ?, cast(? as inet), ?)
                returning id, label, cidr::text as cidr, enabled, created_at, updated_at
                """, ruleId, tenantId, label, cidr, createdBy);
    }

    /** 删除 IP 规则。 */
    public int delete(UUID tenantId, UUID ruleId) {
        return jdbc.update("delete from custody_ip_rule where tenant_id = ? and id = ?",
                tenantId, ruleId);
    }
}
