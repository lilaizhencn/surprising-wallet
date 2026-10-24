package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.IpRuleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** custody_ip_rule 单表仓储，创建和列表共用同一投影。 */
@Repository
public class CustodyIpRuleRepository {
    private final JdbcTemplate jdbc;

    public CustodyIpRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> listActive(UUID tenantId) {
        return jdbc.query("""
                select cidr::text from custody_ip_rule
                 where tenant_id = ? and enabled = true order by cidr
                """, (rs, rowNum) -> rs.getString(1), tenantId);
    }

    public List<IpRuleView> list(UUID tenantId) {
        return jdbc.query("""
                select id, label, cidr::text as cidr, enabled, created_at, updated_at
                  from custody_ip_rule where tenant_id = ? order by created_at desc
                """, CustodyIpRuleRepository::mapRule, tenantId);
    }

    public IpRuleView insert(UUID tenantId, UUID ruleId, String label, String cidr, UUID createdBy) {
        return jdbc.query("""
                insert into custody_ip_rule(id, tenant_id, label, cidr, created_by)
                values (?, ?, ?, cast(? as inet), ?)
                returning id, label, cidr::text as cidr, enabled, created_at, updated_at
                """, CustodyIpRuleRepository::mapRule, ruleId, tenantId, label, cidr, createdBy).getFirst();
    }

    public int delete(UUID tenantId, UUID ruleId) {
        return jdbc.update("delete from custody_ip_rule where tenant_id = ? and id = ?", tenantId, ruleId);
    }

    private static IpRuleView mapRule(ResultSet rs, int rowNum) throws SQLException {
        return new IpRuleView(rs.getObject("id", UUID.class), rs.getString("label"),
                rs.getString("cidr"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
