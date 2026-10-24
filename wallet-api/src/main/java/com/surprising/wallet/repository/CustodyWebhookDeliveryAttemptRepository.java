package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.WebhookDeliveryAttemptView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** custody_webhook_delivery_attempt；尝试主键和全局代次共同约束结果提交。 */
@Repository
public class CustodyWebhookDeliveryAttemptRepository {
    private final JdbcTemplate jdbc;

    public CustodyWebhookDeliveryAttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int insert(UUID id, UUID tenantId, UUID deliveryId, int attemptNumber, int retryCycle,
                      String trigger, String workerId) {
        return jdbc.update("""
                insert into custody_webhook_delivery_attempt(id, tenant_id, delivery_id, attempt_number, retry_cycle, trigger, worker_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, deliveryId, attemptNumber, retryCycle, trigger, workerId);
    }

    public int delivered(UUID tenantId, UUID deliveryId, UUID attemptId, String workerId, int generation,
                         int httpStatus, String response, long durationMs) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt set status = 'DELIVERED', http_status = ?,
                    response_body = ?, completed_at = now(), duration_ms = ?
                 where tenant_id = ? and delivery_id = ? and id = ? and worker_id = ?
                   and attempt_number = ? and status = 'IN_PROGRESS'
                """, httpStatus, response, Math.max(durationMs, 0), tenantId, deliveryId, attemptId, workerId, generation);
    }

    public int failed(UUID tenantId, UUID deliveryId, UUID attemptId, String workerId, int generation,
                      Integer httpStatus, String error, String response, boolean terminal, Instant nextAttemptAt, long durationMs) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt set status = ?, http_status = ?, error_message = ?,
                    response_body = ?, next_attempt_at = ?, completed_at = now(), duration_ms = ?
                 where tenant_id = ? and delivery_id = ? and id = ? and worker_id = ?
                   and attempt_number = ? and status = 'IN_PROGRESS'
                """, terminal ? "FAILED" : "RETRY_SCHEDULED", httpStatus, error, response,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), Math.max(durationMs, 0),
                tenantId, deliveryId, attemptId, workerId, generation);
    }

    public int recoverStale(UUID tenantId, UUID deliveryId) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt
                   set status = 'FAILED', error_message = 'delivery lease expired', completed_at = now()
                 where tenant_id = ? and delivery_id = ? and status = 'IN_PROGRESS'
                """, tenantId, deliveryId);
    }

    public List<WebhookDeliveryAttemptView> list(UUID tenantId, UUID deliveryId, int limit, int offset) {
        return jdbc.query("""
                select id, tenant_id, delivery_id, attempt_number, retry_cycle, trigger, status,
                       worker_id, http_status, error_message, response_body, next_attempt_at,
                       started_at, completed_at, duration_ms
                  from custody_webhook_delivery_attempt where tenant_id = ? and delivery_id = ?
                 order by attempt_number desc, id desc limit ? offset ?
                """, (rs, rowNum) -> new WebhookDeliveryAttemptView(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("delivery_id", UUID.class),
                rs.getInt("attempt_number"), rs.getInt("retry_cycle"), rs.getString("trigger"), rs.getString("status"),
                rs.getString("worker_id"), rs.getObject("http_status", Integer.class), rs.getString("error_message"),
                rs.getString("response_body"), instant(rs, "next_attempt_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), rs.getObject("duration_ms", Long.class)),
                tenantId, deliveryId, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
