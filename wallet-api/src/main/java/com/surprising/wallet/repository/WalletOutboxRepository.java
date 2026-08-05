package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** wallet_outbox 单表仓储。 */
@Repository
public class WalletOutboxRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Outbox 仓储。 */
    public WalletOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入一条待派发消息。 */
    public int insert(UUID id, UUID tenantId, String topic, String aggregateType,
                      String aggregateId, String dedupeKey, String payload) {
        return jdbc.update("""
                insert into wallet_outbox(
                    id, tenant_id, topic, aggregate_type, aggregate_id, dedupe_key, payload,
                    status, attempt_count, next_attempt_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, now(), now(), now())
                on conflict (topic, dedupe_key) do nothing
                """, id, tenantId, topic, aggregateType, aggregateId, dedupeKey, payload);
    }

    /** 原子领取一批待派发消息，并恢复超时的派发任务。 */
    @Transactional(rollbackFor = Throwable.class)
    public List<OutboxRecord> claim(String topic, String workerId, int limit) {
        List<OutboxRecord> records = jdbc.query("""
                select id, tenant_id, topic, aggregate_type, aggregate_id, dedupe_key, payload::text,
                       attempt_count
                  from wallet_outbox
                 where topic = ?
                   and ((status in ('PENDING', 'FAILED') and next_attempt_at <= now())
                    or (status = 'DISPATCHING' and locked_at < now() - interval '5 minutes'))
                 order by next_attempt_at, created_at, id
                 limit ? for update skip locked
                """, (rs, rowNum) -> map(rs, rowNum), topic, Math.min(Math.max(limit, 1), 200));
        for (OutboxRecord record : records) {
            jdbc.update("""
                    update wallet_outbox
                       set status = 'DISPATCHING', locked_by = ?, locked_at = now(),
                           attempt_count = attempt_count + 1, updated_at = now()
                     where id = ?
                       and ((status in ('PENDING', 'FAILED') and next_attempt_at <= now())
                        or (status = 'DISPATCHING' and locked_at < now() - interval '5 minutes'))
                    """, workerId, record.id());
        }
        return records;
    }

    /** 标记消息派发成功，仅允许当前租约持有者提交结果。 */
    public int markDispatched(UUID id, String workerId) {
        return jdbc.update("""
                update wallet_outbox
                   set status = 'DISPATCHED', locked_by = null, locked_at = null,
                       dispatched_at = now(), updated_at = now(), last_error = null
                 where id = ? and status = 'DISPATCHING' and locked_by = ?
                """, id, workerId);
    }

    /** 记录派发失败并安排指数退避重试或死信。 */
    public int markFailed(UUID id, String workerId, String error,
                          Duration retryDelay, boolean dead) {
        String status = dead ? "DEAD" : "FAILED";
        return jdbc.update("""
                update wallet_outbox
                   set status = ?, locked_by = null, locked_at = null,
                       next_attempt_at = ?, last_error = ?, updated_at = now()
                 where id = ? and status = 'DISPATCHING' and locked_by = ?
                """, status, Timestamp.from(Instant.now().plus(retryDelay)), error, id, workerId);
    }

    /** Outbox 记录。 */
    public record OutboxRecord(UUID id, UUID tenantId, String topic, String aggregateType,
                               String aggregateId, String dedupeKey, String payload,
                               int attemptCount) {
    }

    /** 映射 Outbox 记录。 */
    private static OutboxRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("topic"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("dedupe_key"),
                rs.getString("payload"),
                rs.getInt("attempt_count"));
    }
}
