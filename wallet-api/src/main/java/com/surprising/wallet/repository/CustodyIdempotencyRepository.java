package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_idempotency_key 单表仓储。 */
@Repository
public class CustodyIdempotencyRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造幂等键仓储。 */
    public CustodyIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询未过期幂等记录。 */
    public List<Map<String, Object>> find(UUID tenantId, String key, String operation) {
        return jdbc.queryForList("""
                select request_hash, response_status, response_body::text as response_json, expires_at
                  from custody_idempotency_key
                 where tenant_id = ? and idempotency_key = ? and operation = ? and expires_at > now()
                """, tenantId, key, operation);
    }

    /** 开始幂等请求，返回是否成功占用。 */
    public boolean begin(UUID tenantId, String key, String operation, String requestHash, Instant expiresAt) {
        int count = jdbc.update("""
                insert into custody_idempotency_key(tenant_id, idempotency_key, operation, request_hash, expires_at)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id, idempotency_key, operation) do nothing
                """, tenantId, key, operation, requestHash, expiresAt);
        return count == 1;
    }

    /** 完成幂等请求并保存响应。 */
    public int complete(UUID tenantId, String key, String operation, int responseStatus, String responseJson) {
        return jdbc.update("""
                update custody_idempotency_key set response_status = ?, response_body = ?::jsonb
                 where tenant_id = ? and idempotency_key = ? and operation = ?
                """, responseStatus, responseJson, tenantId, key, operation);
    }

    /** 删除已过期的幂等记录。 */
    public int deleteExpired() {
        return jdbc.update("delete from custody_idempotency_key where expires_at < now()");
    }
}
