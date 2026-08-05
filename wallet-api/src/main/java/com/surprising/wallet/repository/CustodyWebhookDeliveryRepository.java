package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_webhook_delivery 单表仓储。 */
@Repository
public class CustodyWebhookDeliveryRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Webhook 投递仓储。 */
    public CustodyWebhookDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建 Webhook 投递记录。 */
    public int insert(UUID id, UUID tenantId, UUID endpointId, UUID eventId) {
        return jdbc.update("""
                insert into custody_webhook_delivery(id, tenant_id, endpoint_id, event_id, status,
                    next_attempt_at, next_attempt_trigger)
                values (?, ?, ?, ?, 'PENDING', now(), 'AUTOMATIC')
                """, id, tenantId, endpointId, eventId);
    }

    /** 查询租户端点的投递记录。 */
    public List<Map<String, Object>> list(UUID tenantId, UUID endpointId, String status,
                                          int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, endpoint_id, event_id, status, attempt_count,
                       total_attempt_count, manual_retry_count, next_attempt_trigger,
                       locked_by, locked_at, last_http_status, last_error, last_response,
                       delivered_at, created_at, updated_at
                  from custody_webhook_delivery
                 where tenant_id = ?
                   and (cast(? as uuid) is null or endpoint_id = cast(? as uuid))
                   and (cast(? as varchar) is null or status = cast(? as varchar))
                 order by created_at desc, id desc limit ? offset ?
                """, tenantId, endpointId, endpointId, status, status,
                Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 统计租户失败的 Webhook 投递。 */
    public long countFailed(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_webhook_delivery
                 where tenant_id = ? and status = 'FAILED'
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    /** 原子领取待投递记录并返回本次领取的单表字段。 */
    @Transactional(rollbackFor = Throwable.class)
    public List<Map<String, Object>> claim(String workerId, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                with queued as (
                    select id, row_number() over (partition by tenant_id order by next_attempt_at, id)
                        as tenant_rank
                      from custody_webhook_delivery
                     where (status in ('PENDING', 'RETRY') and next_attempt_at <= now())
                        or (status = 'DELIVERING' and locked_at < now() - interval '5 minutes')
                ), candidates as (
                    select id from queued order by tenant_rank, id limit ?
                )
                select d.id, d.tenant_id, d.endpoint_id, d.event_id, d.attempt_count, d.total_attempt_count,
                       d.manual_retry_count,
                       case when d.status = 'DELIVERING' then 'RECOVERY' else d.next_attempt_trigger end
                           as next_attempt_trigger
                  from custody_webhook_delivery d
                  join candidates c on c.id = d.id
                 order by d.next_attempt_at, d.id
                 for update of d skip locked
                """, Math.min(Math.max(limit, 1), 100));
        for (Map<String, Object> row : rows) {
            jdbc.update("""
                    update custody_webhook_delivery set status = 'DELIVERING', locked_by = ?, locked_at = now(),
                        attempt_count = attempt_count + 1, total_attempt_count = total_attempt_count + 1,
                        updated_at = now()
                     where id = ?
                       and ((status in ('PENDING', 'RETRY') and next_attempt_at <= now())
                            or (status = 'DELIVERING' and locked_at < now() - interval '5 minutes'))
                    """, workerId, row.get("id"));
        }
        return rows;
    }

    /** 标记投递成功。 */
    public int delivered(UUID tenantId, UUID id, int httpStatus, String response) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'DELIVERED', last_http_status = ?,
                    last_response = ?, last_error = null, delivered_at = now(), locked_by = null,
                    locked_at = null, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'DELIVERING'
                """, httpStatus, response, tenantId, id);
    }

    /** 按工作者租约标记投递成功，防止旧工作者覆盖恢复后的结果。 */
    public int delivered(UUID tenantId, UUID id, String workerId, int httpStatus, String response) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'DELIVERED', last_http_status = ?,
                    last_response = ?, last_error = null, delivered_at = now(), locked_by = null,
                    locked_at = null, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'DELIVERING' and locked_by = ?
                """, httpStatus, response, tenantId, id, workerId);
    }

    /** 标记投递失败并安排重试或终态。 */
    public int failed(UUID tenantId, UUID id, Integer httpStatus, String error, String response,
                      boolean terminal, java.sql.Timestamp nextAttemptAt, long durationMs) {
        String status = terminal ? "FAILED" : "RETRY";
        return jdbc.update("""
                update custody_webhook_delivery set status = ?, last_http_status = ?, last_error = ?,
                    last_response = ?, next_attempt_at = ?, locked_by = null, locked_at = null, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'DELIVERING'
                """, status, httpStatus, error, response, nextAttemptAt, tenantId, id);
    }

    /** 按工作者租约标记投递失败，防止旧工作者覆盖恢复后的结果。 */
    public int failed(UUID tenantId, UUID id, String workerId, Integer httpStatus, String error,
                      String response, boolean terminal, java.sql.Timestamp nextAttemptAt) {
        String status = terminal ? "FAILED" : "RETRY";
        return jdbc.update("""
                update custody_webhook_delivery set status = ?, last_http_status = ?, last_error = ?,
                    last_response = ?, next_attempt_at = ?, locked_by = null, locked_at = null, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'DELIVERING' and locked_by = ?
                """, status, httpStatus, error, response, nextAttemptAt, tenantId, id, workerId);
    }

    /** 手动重试投递。 */
    public int retry(UUID tenantId, UUID id) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'RETRY', next_attempt_at = now(),
                    next_attempt_trigger = 'MANUAL', manual_retry_count = manual_retry_count + 1,
                    last_error = null, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('FAILED', 'DELIVERED', 'RETRY')
                """, tenantId, id);
    }

    /** 批量重试端点失败投递。 */
    public int retryFailed(UUID tenantId, UUID endpointId) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'RETRY', next_attempt_at = now(),
                    next_attempt_trigger = 'MANUAL', manual_retry_count = manual_retry_count + 1,
                    last_error = null, updated_at = now()
                 where tenant_id = ? and endpoint_id = ? and status in ('FAILED', 'RETRY')
                """, tenantId, endpointId);
    }
}
