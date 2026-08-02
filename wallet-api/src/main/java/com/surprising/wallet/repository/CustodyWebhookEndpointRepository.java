package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_webhook_endpoint 单表仓储。 */
@Repository
public class CustodyWebhookEndpointRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Webhook 端点仓储。 */
    public CustodyWebhookEndpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建 Webhook 端点。 */
    public int insert(UUID id, UUID tenantId, String name, String url, String secretCiphertext,
                      String verificationTokenHash) {
        return jdbc.update("""
                insert into custody_webhook_endpoint(id, tenant_id, name, url, secret_ciphertext,
                    verification_token_hash, status)
                values (?, ?, ?, ?, ?, ?, 'PENDING_VERIFICATION')
                """, id, tenantId, name, url, secretCiphertext, verificationTokenHash);
    }

    /** 查询租户 Webhook 端点。 */
    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.queryForList("""
                select id, tenant_id, name, url, secret_ciphertext, status, verification_token_hash,
                       verified_at, last_delivery_at, created_at, updated_at
                  from custody_webhook_endpoint where tenant_id = ? order by created_at desc, id
                """, tenantId);
    }

    /** 统计租户启用的 Webhook 端点。 */
    public long countActive(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_webhook_endpoint
                 where tenant_id = ? and status = 'ACTIVE'
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    /** 按主键查询 Webhook 端点。 */
    public List<Map<String, Object>> find(UUID tenantId, UUID id) {
        return jdbc.queryForList("""
                select id, tenant_id, name, url, secret_ciphertext, status, verification_token_hash,
                       verified_at, last_delivery_at, created_at, updated_at
                  from custody_webhook_endpoint where tenant_id = ? and id = ?
                """, tenantId, id);
    }

    /** 标记 Webhook 已验证。 */
    public int markVerified(UUID tenantId, UUID id) {
        return jdbc.update("""
                update custody_webhook_endpoint set status = 'ACTIVE', verified_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and status = 'PENDING_VERIFICATION'
                """, tenantId, id);
    }

    /** 更新 Webhook 状态。 */
    public int updateStatus(UUID tenantId, UUID id, String status) {
        return jdbc.update("update custody_webhook_endpoint set status = ?, updated_at = now() where tenant_id = ? and id = ?",
                status, tenantId, id);
    }

    /** 更新最近投递时间。 */
    public int touchDelivery(UUID tenantId, UUID id) {
        return jdbc.update("update custody_webhook_endpoint set last_delivery_at = now(), updated_at = now() where tenant_id = ? and id = ?",
                tenantId, id);
    }
}
