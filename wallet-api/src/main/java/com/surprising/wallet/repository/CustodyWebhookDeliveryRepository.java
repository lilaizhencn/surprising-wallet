package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.WebhookDeliveryClaim;
import com.surprising.wallet.model.custody.WebhookDeliveryRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** custody_webhook_delivery 单表状态与租约；全局尝试编号同时作为领取代次。 */
@Repository
public class CustodyWebhookDeliveryRepository {
    private final JdbcTemplate jdbc;

    public CustodyWebhookDeliveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int insert(UUID id, UUID tenantId, UUID endpointId, UUID eventId) {
        return jdbc.update("""
                insert into custody_webhook_delivery(id, tenant_id, endpoint_id, event_id, status,
                    next_attempt_at, next_attempt_trigger)
                values (?, ?, ?, ?, 'PENDING', now(), 'AUTOMATIC')
                on conflict (endpoint_id, event_id) do nothing
                """, id, tenantId, endpointId, eventId);
    }

    public List<WebhookDeliveryRecord> list(UUID tenantId, UUID endpointId, String status, int limit, int offset) {
        return jdbc.query("""
                select id, tenant_id, endpoint_id, event_id, status, attempt_count, total_attempt_count,
                       manual_retry_count, next_attempt_at, next_attempt_trigger, locked_by, locked_at,
                       last_http_status, last_error, last_response, delivered_at, created_at, updated_at
                  from custody_webhook_delivery
                 where tenant_id = ?
                   and (cast(? as uuid) is null or endpoint_id = cast(? as uuid))
                   and (cast(? as varchar) is null or status = cast(? as varchar))
                 order by created_at desc, id desc limit ? offset ?
                """, CustodyWebhookDeliveryRepository::mapDelivery, tenantId, endpointId, endpointId,
                status, status, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    public long countFailed(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from custody_webhook_delivery where tenant_id = ? and status = 'FAILED'",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    /** 锁定、领取和计数在一条语句内完成，返回更新后的代次。 */
    public List<WebhookDeliveryClaim> claim(String workerId, int limit) {
        return jdbc.query("""
                with queued as (
                    select id, row_number() over (partition by tenant_id order by next_attempt_at, id) as tenant_rank
                      from custody_webhook_delivery
                     where (status in ('PENDING', 'RETRY') and next_attempt_at <= now())
                        or (status = 'DELIVERING' and locked_at < now() - interval '5 minutes')
                ), candidates as (
                    select d.id from custody_webhook_delivery d join queued q on q.id = d.id
                     order by q.tenant_rank, d.next_attempt_at, d.id limit ?
                     for update of d skip locked
                )
                update custody_webhook_delivery d
                   set next_attempt_trigger = case when d.status = 'DELIVERING' then 'RECOVERY' else d.next_attempt_trigger end,
                       status = 'DELIVERING', locked_by = ?, locked_at = now(),
                       attempt_count = d.attempt_count + 1, total_attempt_count = d.total_attempt_count + 1,
                       updated_at = now()
                  from candidates c
                 where d.id = c.id
                   and ((d.status in ('PENDING', 'RETRY') and d.next_attempt_at <= now())
                        or (d.status = 'DELIVERING' and d.locked_at < now() - interval '5 minutes'))
                returning d.id, d.tenant_id, d.endpoint_id, d.event_id, d.attempt_count,
                          d.total_attempt_count, d.manual_retry_count, d.next_attempt_trigger
                """, (rs, rowNum) -> new WebhookDeliveryClaim(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("endpoint_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getInt("attempt_count"), rs.getInt("total_attempt_count"),
                rs.getInt("manual_retry_count"), rs.getString("next_attempt_trigger")), Math.min(Math.max(limit, 1), 100), workerId);
    }

    public int delivered(UUID tenantId, UUID id, UUID endpointId, UUID eventId, String workerId,
                         int generation, int httpStatus, String response) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'DELIVERED', last_http_status = ?,
                    last_response = ?, last_error = null, delivered_at = now(), next_attempt_at = null,
                    locked_by = null, locked_at = null, updated_at = now()
                 where tenant_id = ? and id = ? and endpoint_id = ? and event_id = ?
                   and status = 'DELIVERING' and locked_by = ? and total_attempt_count = ?
                """, httpStatus, response, tenantId, id, endpointId, eventId, workerId, generation);
    }

    public int failed(UUID tenantId, UUID id, UUID endpointId, UUID eventId, String workerId,
                      int generation, Integer httpStatus, String error, String response, boolean terminal, Instant nextAttemptAt) {
        return jdbc.update("""
                update custody_webhook_delivery set status = ?, last_http_status = ?, last_error = ?,
                    last_response = ?, next_attempt_at = ?, next_attempt_trigger = 'AUTOMATIC',
                    locked_by = null, locked_at = null, updated_at = now()
                 where tenant_id = ? and id = ? and endpoint_id = ? and event_id = ?
                   and status = 'DELIVERING' and locked_by = ? and total_attempt_count = ?
                """, terminal ? "FAILED" : "RETRY", httpStatus, error, response,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), tenantId, id, endpointId, eventId, workerId, generation);
    }

    public int retry(UUID tenantId, UUID id) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'RETRY', next_attempt_at = now(),
                    next_attempt_trigger = 'MANUAL', attempt_count = 0,
                    manual_retry_count = manual_retry_count + 1, last_error = null, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('FAILED', 'DELIVERED', 'RETRY')
                """, tenantId, id);
    }

    public int retryFailed(UUID tenantId, UUID endpointId) {
        return jdbc.update("""
                update custody_webhook_delivery set status = 'RETRY', next_attempt_at = now(),
                    next_attempt_trigger = 'MANUAL', attempt_count = 0,
                    manual_retry_count = manual_retry_count + 1, last_error = null, updated_at = now()
                 where tenant_id = ? and endpoint_id = ? and status in ('FAILED', 'RETRY')
                """, tenantId, endpointId);
    }

    private static WebhookDeliveryRecord mapDelivery(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookDeliveryRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getObject("event_id", UUID.class), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getInt("total_attempt_count"), rs.getInt("manual_retry_count"),
                instant(rs, "next_attempt_at"), rs.getString("next_attempt_trigger"), rs.getString("locked_by"),
                instant(rs, "locked_at"), rs.getObject("last_http_status", Integer.class), rs.getString("last_error"),
                rs.getString("last_response"), instant(rs, "delivered_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
