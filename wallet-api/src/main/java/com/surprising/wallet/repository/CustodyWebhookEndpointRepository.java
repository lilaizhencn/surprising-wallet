package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.WebhookEndpointRecord;
import com.surprising.wallet.model.custody.WebhookEndpointView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** custody_webhook_endpoint；公开投影不含密钥。 */
@Repository
public class CustodyWebhookEndpointRepository {
    private static final String PUBLIC_COLUMNS = "id, tenant_id, name, url, status, verified_at, "
            + "last_delivery_at, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public CustodyWebhookEndpointRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public WebhookEndpointView insert(UUID id, UUID tenantId, String name, String url,
                                      String secretCiphertext, UUID createdBy) {
        return jdbc.query("""
                insert into custody_webhook_endpoint(id, tenant_id, name, url, secret_ciphertext, created_by, status)
                values (?, ?, ?, ?, ?, ?, 'PENDING_VERIFICATION')
                returning
                """ + PUBLIC_COLUMNS, CustodyWebhookEndpointRepository::mapView,
                id, tenantId, name, url, secretCiphertext, createdBy).getFirst();
    }

    public List<WebhookEndpointView> list(UUID tenantId) {
        return jdbc.query("select " + PUBLIC_COLUMNS + " from custody_webhook_endpoint where tenant_id = ? order by created_at desc, id",
                CustodyWebhookEndpointRepository::mapView, tenantId);
    }

    public Optional<WebhookEndpointView> findView(UUID tenantId, UUID id) {
        return jdbc.query("select " + PUBLIC_COLUMNS + " from custody_webhook_endpoint where tenant_id = ? and id = ?",
                CustodyWebhookEndpointRepository::mapView, tenantId, id).stream().findFirst();
    }

    public Optional<WebhookEndpointRecord> find(UUID tenantId, UUID id) {
        return jdbc.query("select " + PUBLIC_COLUMNS + ", secret_ciphertext from custody_webhook_endpoint where tenant_id = ? and id = ?",
                CustodyWebhookEndpointRepository::mapEndpoint, tenantId, id).stream().findFirst();
    }

    public Map<UUID, WebhookEndpointRecord> findAll(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String array = ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return jdbc.query("select " + PUBLIC_COLUMNS + ", secret_ciphertext from custody_webhook_endpoint where tenant_id = ? and id = any(cast(? as uuid[]))",
                CustodyWebhookEndpointRepository::mapEndpoint, tenantId, array).stream()
                .collect(Collectors.toMap(WebhookEndpointRecord::id, row -> row));
    }

    public List<UUID> listActiveIds(UUID tenantId) {
        return jdbc.queryForList("select id from custody_webhook_endpoint where tenant_id = ? and status = 'ACTIVE' order by created_at desc, id",
                UUID.class, tenantId);
    }

    public long countActive(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from custody_webhook_endpoint where tenant_id = ? and status = 'ACTIVE'",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public int markVerified(UUID tenantId, UUID id) {
        return jdbc.update("""
                update custody_webhook_endpoint set status = 'ACTIVE', verified_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and status = 'PENDING_VERIFICATION'
                """, tenantId, id);
    }

    public int updateStatus(UUID tenantId, UUID id, String status) {
        return jdbc.update("update custody_webhook_endpoint set status = ?, updated_at = now() where tenant_id = ? and id = ?",
                status, tenantId, id);
    }

    public int touchDelivery(UUID tenantId, UUID id) {
        return jdbc.update("update custody_webhook_endpoint set last_delivery_at = now(), updated_at = now() where tenant_id = ? and id = ?",
                tenantId, id);
    }

    private static WebhookEndpointRecord mapEndpoint(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookEndpointRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("url"), rs.getString("secret_ciphertext"), rs.getString("status"),
                instant(rs, "verified_at"), instant(rs, "last_delivery_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static WebhookEndpointView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookEndpointView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("url"), rs.getString("status"), instant(rs, "verified_at"),
                instant(rs, "last_delivery_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
