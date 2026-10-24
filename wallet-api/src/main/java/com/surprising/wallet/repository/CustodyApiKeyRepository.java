package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.ApiKeyRecord;
import com.surprising.wallet.model.custody.ApiKeyView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** custody_api_key 单表仓储，列表投影不含密钥材料。 */
@Repository
public class CustodyApiKeyRepository {
    private final JdbcTemplate jdbc;

    public CustodyApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApiKeyRecord insert(UUID id, UUID tenantId, String keyId, String name,
                               String encryptedSecret, UUID createdBy) {
        return jdbc.query("""
                insert into custody_api_key(id, tenant_id, key_id, name, secret_ciphertext, created_by)
                values (?, ?, ?, ?, ?, ?)
                returning id, tenant_id, key_id, name, secret_ciphertext, status, expires_at, created_at
                """, CustodyApiKeyRepository::mapKey,
                id, tenantId, keyId, name, encryptedSecret, createdBy).getFirst();
    }

    public Optional<ApiKeyRecord> findActiveByKeyId(String keyId) {
        return jdbc.query("""
                select id, tenant_id, key_id, name, secret_ciphertext, status, expires_at, created_at
                  from custody_api_key
                 where key_id = ? and status = 'ACTIVE'
                   and (expires_at is null or expires_at > now())
                """, CustodyApiKeyRepository::mapKey, keyId).stream().findFirst();
    }

    public List<ApiKeyView> listByTenant(UUID tenantId) {
        return jdbc.query("""
                select id, key_id, name, status, last_used_at, last_used_ip, expires_at, created_at, revoked_at
                  from custody_api_key where tenant_id = ? order by created_at desc
                """, (rs, rowNum) -> new ApiKeyView(rs.getObject("id", UUID.class),
                rs.getString("key_id"), rs.getString("name"), rs.getString("status"),
                instant(rs, "last_used_at"), rs.getString("last_used_ip"), instant(rs, "expires_at"),
                instant(rs, "created_at"), instant(rs, "revoked_at")), tenantId);
    }

    public long countActive(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_api_key where tenant_id = ? and status = 'ACTIVE'
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    public int revoke(UUID tenantId, UUID keyId) {
        return jdbc.update("""
                update custody_api_key set status = 'REVOKED', revoked_at = now()
                 where tenant_id = ? and id = ? and status = 'ACTIVE'
                """, tenantId, keyId);
    }

    public void touch(UUID keyId, String sourceIp) {
        jdbc.update("""
                update custody_api_key
                   set last_used_at = now(), last_used_ip = cast(nullif(?, '') as inet)
                 where id = ?
                   and (last_used_at is null or last_used_at < now() - interval '1 minute'
                        or last_used_ip is distinct from cast(nullif(?, '') as inet))
                """, sourceIp, keyId, sourceIp);
    }

    private static ApiKeyRecord mapKey(ResultSet rs, int rowNum) throws SQLException {
        return new ApiKeyRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("key_id"), rs.getString("name"), rs.getString("secret_ciphertext"),
                rs.getString("status"), instant(rs, "expires_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
