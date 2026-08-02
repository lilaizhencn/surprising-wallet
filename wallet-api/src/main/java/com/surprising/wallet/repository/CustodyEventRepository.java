package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

/** custody_event 单表仓储。 */
@Repository
public class CustodyEventRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造领域事件仓储。 */
    public CustodyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建领域事件。 */
    public int insert(UUID id, UUID tenantId, String eventType, String aggregateType,
                      String aggregateId, String payload) {
        return jdbc.update("""
                insert into custody_event(id, tenant_id, event_type, aggregate_type, aggregate_id, payload)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """, id, tenantId, eventType, aggregateType, aggregateId, payload);
    }

    /** 幂等创建领域事件，返回实际保存的事件主键。 */
    public Optional<UUID> insertIfAbsent(UUID id, UUID tenantId, String eventType,
                                         String aggregateType, String aggregateId, String payload) {
        return jdbc.query("""
                insert into custody_event(id, tenant_id, event_type, aggregate_type, aggregate_id, payload)
                values (?, ?, ?, ?, ?, ?::jsonb)
                on conflict (tenant_id, event_type, aggregate_type, aggregate_id) do nothing
                returning id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                id, tenantId, eventType, aggregateType, aggregateId, payload).stream().findFirst();
    }

    /** 按事件业务键查询领域事件。 */
    public Optional<UUID> findIdByBusinessKey(UUID tenantId, String eventType,
                                              String aggregateType, String aggregateId) {
        return jdbc.query("""
                select id from custody_event
                 where tenant_id = ? and event_type = ? and aggregate_type = ? and aggregate_id = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId, eventType, aggregateType, aggregateId).stream().findFirst();
    }

    /** 查询事件完整字段，不限制事件发布状态。 */
    public Optional<Map<String, Object>> find(UUID tenantId, UUID id) {
        return jdbc.queryForList("""
                select id, tenant_id, event_type, aggregate_type, aggregate_id, payload::text as payload,
                       status, occurred_at, published_at, created_at
                  from custody_event where tenant_id = ? and id = ?
                """, tenantId, id).stream().findFirst();
    }

    /** 查询待发布领域事件。 */
    public List<Map<String, Object>> listPending(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                select id, tenant_id, event_type, aggregate_type, aggregate_id, payload::text as payload,
                       status, occurred_at, published_at, created_at
                  from custody_event where tenant_id = ? and status = 'PENDING'
                 order by occurred_at, id limit ?
                """, tenantId, Math.min(Math.max(limit, 1), 500));
    }

    /** 标记领域事件已发布。 */
    public int markPublished(UUID id) {
        return jdbc.update("update custody_event set status = 'PUBLISHED', published_at = now() where id = ? and status = 'PENDING'",
                id);
    }
}
