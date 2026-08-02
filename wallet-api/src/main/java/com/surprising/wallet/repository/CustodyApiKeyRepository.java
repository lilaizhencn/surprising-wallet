package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_api_key 单表仓储。 */
@Repository
public class CustodyApiKeyRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 API 密钥单表仓储。 */
    public CustodyApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入 API 密钥。 */
    public void insert(UUID id, UUID tenantId, String keyId, String name,
                       String encryptedSecret, UUID createdBy) {
        jdbc.update("""
                insert into custody_api_key(
                    id, tenant_id, key_id, name, secret_ciphertext, created_by)
                values (?, ?, ?, ?, ?, ?)
                """, id, tenantId, keyId, name, encryptedSecret, createdBy);
    }

    /** 按密钥编号查询单表字段。 */
    public List<Map<String, Object>> findByKeyId(String keyId) {
        return jdbc.queryForList("""
                select id, tenant_id, key_id, name, secret_ciphertext, status,
                       expires_at, created_at
                  from custody_api_key
                 where key_id = ?
                """, keyId);
    }

    /** 查询有效租户 API 密钥。 */
    public List<Map<String, Object>> findActiveByKeyId(String keyId) {
        return jdbc.queryForList("""
                select id, tenant_id, key_id, name, secret_ciphertext, status,
                       expires_at, created_at
                  from custody_api_key
                 where key_id = ? and status = 'ACTIVE'
                   and (expires_at is null or expires_at > now())
                """, keyId);
    }

    /** 查询租户 API 密钥列表。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, key_id, name, status, last_used_at, last_used_ip,
                       expires_at, created_at, revoked_at
                  from custody_api_key
                 where tenant_id = ? order by created_at desc
                """, tenantId);
    }

    /** 统计租户有效 API 密钥。 */
    public long countActive(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_api_key
                 where tenant_id = ? and status = 'ACTIVE'
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    /** 撤销租户 API 密钥。 */
    public int revoke(UUID tenantId, UUID keyId) {
        return jdbc.update("""
                update custody_api_key set status = 'REVOKED', revoked_at = now()
                 where tenant_id = ? and id = ? and status = 'ACTIVE'
                """, tenantId, keyId);
    }

    /** 更新密钥最近使用信息。 */
    public void touch(UUID keyId, String sourceIp) {
        jdbc.update("""
                update custody_api_key
                   set last_used_at = now(), last_used_ip = cast(nullif(?, '') as inet)
                 where id = ?
                   and (last_used_at is null or last_used_at < now() - interval '1 minute'
                        or last_used_ip is distinct from cast(nullif(?, '') as inet))
                """, sourceIp, keyId, sourceIp);
    }
}
