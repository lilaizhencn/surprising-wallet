package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_audit_log 单表仓储。 */
@Repository
public class CustodyAuditRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造审计日志仓储。 */
    public CustodyAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入审计日志。 */
    public int insert(UUID id, UUID tenantId, String actorType, String actorId, String action,
                      String resourceType, String resourceId, String sourceIp, String details) {
        return jdbc.update("""
                insert into custody_audit_log(id, tenant_id, actor_type, actor_id, action,
                    resource_type, resource_id, source_ip, details)
                values (?, ?, ?, ?, ?, ?, ?, ?::inet, coalesce(?::jsonb, '{}'::jsonb))
                """, id, tenantId, actorType, actorId, action, resourceType, resourceId, sourceIp, details);
    }

    /** 查询租户审计日志。 */
    public List<Map<String, Object>> list(UUID tenantId, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, actor_type, actor_id, action, resource_type, resource_id,
                       source_ip::text as source_ip, details::text as details, created_at
                  from custody_audit_log where tenant_id = ?
                 order by created_at desc, id desc limit ? offset ?
                """, tenantId, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 查询平台审计日志。 */
    public List<Map<String, Object>> listPlatform(int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, actor_type, actor_id, action, resource_type, resource_id,
                       source_ip::text as source_ip, details::text as details, created_at
                  from custody_audit_log where tenant_id is null
                 order by created_at desc, id desc limit ? offset ?
                """, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 统计租户审计记录数量。 */
    public long count(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    /** 统计平台审计记录数量。 */
    public long countPlatform() {
        Long count = jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id is null", Long.class);
        return count == null ? 0 : count;
    }
}
