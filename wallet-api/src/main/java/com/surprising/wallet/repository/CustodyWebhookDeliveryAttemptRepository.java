package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_webhook_delivery_attempt 单表仓储。 */
@Repository
public class CustodyWebhookDeliveryAttemptRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Webhook 投递尝试仓储。 */
    public CustodyWebhookDeliveryAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建投递尝试。 */
    public int insert(UUID id, UUID tenantId, UUID deliveryId, int attemptNumber, int retryCycle,
                      String trigger, String workerId) {
        return jdbc.update("""
                insert into custody_webhook_delivery_attempt(
                    id, tenant_id, delivery_id, attempt_number, retry_cycle, trigger, worker_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, deliveryId, attemptNumber, retryCycle, trigger, workerId);
    }

    /** 标记投递尝试成功。 */
    public int delivered(UUID tenantId, UUID attemptId, int httpStatus, String response, long durationMs) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt set status = 'DELIVERED', http_status = ?,
                    response_body = ?, completed_at = now(), duration_ms = ?
                 where tenant_id = ? and id = ? and status = 'IN_PROGRESS'
                """, httpStatus, response, Math.max(durationMs, 0), tenantId, attemptId);
    }

    /** 按工作者标记尝试成功，避免旧工作者提交结果。 */
    public int delivered(UUID tenantId, UUID attemptId, String workerId,
                         int httpStatus, String response, long durationMs) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt set status = 'DELIVERED', http_status = ?,
                    response_body = ?, completed_at = now(), duration_ms = ?
                 where tenant_id = ? and id = ? and status = 'IN_PROGRESS' and worker_id = ?
                """, httpStatus, response, Math.max(durationMs, 0), tenantId, attemptId, workerId);
    }

    /** 标记投递尝试失败。 */
    public int failed(UUID tenantId, UUID attemptId, Integer httpStatus, String error,
                      String response, boolean terminal, java.sql.Timestamp nextAttemptAt, long durationMs) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt set status = ?, http_status = ?, error_message = ?,
                    response_body = ?, next_attempt_at = ?, completed_at = now(), duration_ms = ?
                 where tenant_id = ? and id = ? and status = 'IN_PROGRESS'
                """, terminal ? "FAILED" : "RETRY_SCHEDULED", httpStatus, error, response, nextAttemptAt,
                Math.max(durationMs, 0), tenantId, attemptId);
    }

    /** 按工作者标记尝试失败，避免旧工作者提交结果。 */
    public int failed(UUID tenantId, UUID attemptId, String workerId, Integer httpStatus, String error,
                      String response, boolean terminal, java.sql.Timestamp nextAttemptAt, long durationMs) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt set status = ?, http_status = ?, error_message = ?,
                    response_body = ?, next_attempt_at = ?, completed_at = now(), duration_ms = ?
                 where tenant_id = ? and id = ? and status = 'IN_PROGRESS' and worker_id = ?
                """, terminal ? "FAILED" : "RETRY_SCHEDULED", httpStatus, error, response, nextAttemptAt,
                Math.max(durationMs, 0), tenantId, attemptId, workerId);
    }

    /** 将租约过期的旧尝试标记为失败。 */
    public int recoverStale(UUID tenantId, UUID deliveryId) {
        return jdbc.update("""
                update custody_webhook_delivery_attempt
                   set status = 'FAILED', error_message = 'delivery lease expired', completed_at = now()
                 where tenant_id = ? and delivery_id = ? and status = 'IN_PROGRESS'
                """, tenantId, deliveryId);
    }

    /** 查询投递尝试。 */
    public List<Map<String, Object>> list(UUID tenantId, UUID deliveryId, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, delivery_id, attempt_number, retry_cycle, trigger, status,
                       worker_id, http_status, error_message, response_body, next_attempt_at,
                       started_at, completed_at, duration_ms
                  from custody_webhook_delivery_attempt
                 where tenant_id = ? and delivery_id = ?
                 order by attempt_number desc, id desc limit ? offset ?
                """, tenantId, deliveryId, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }
}
