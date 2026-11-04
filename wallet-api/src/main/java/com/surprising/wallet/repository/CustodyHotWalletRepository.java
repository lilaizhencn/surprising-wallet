package com.surprising.wallet.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns only custody_hot_wallet; address and key ownership are checked by the service. */
@Repository
public class CustodyHotWalletRepository {
    private static final String COLUMNS = "id, tenant_id, custody_address_id, mpc_key_id, chain, network, label, "
            + "status, route_priority, quarantine_actor_id, quarantine_at, quarantine_reason, "
            + "quarantine_fence_confirmed_at, created_by, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public CustodyHotWalletRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Wallet(UUID id, UUID tenantId, UUID custodyAddressId, UUID mpcKeyId, String chain,
                         String network, String label, String status, int routePriority,
                         UUID quarantineActorId, Instant quarantineAt, String quarantineReason,
                         Instant quarantineFenceConfirmedAt,
                         UUID createdBy, Instant createdAt, Instant updatedAt) { }

    public List<Wallet> list(UUID tenantId, String chain) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where tenant_id=? and chain=? "
                        + "order by route_priority,id", CustodyHotWalletRepository::map, tenantId, chain);
    }
    public List<Wallet> pendingApprovalQuarantine(int limit) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where status='QUARANTINED' "
                        + "and quarantine_fence_confirmed_at is null order by quarantine_at,id limit ?",
                CustodyHotWalletRepository::map, Math.min(Math.max(limit, 1), 100));
    }
    public List<Wallet> activeAfter(UUID afterId,int limit) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where status='ACTIVE' "
                        + "and (?::uuid is null or id>?) order by id limit ?",
                CustodyHotWalletRepository::map,afterId,afterId,Math.min(Math.max(limit,1),100));
    }

    public Optional<Wallet> find(UUID tenantId, UUID id) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where tenant_id=? and id=?",
                CustodyHotWalletRepository::map, tenantId, id).stream().findFirst();
    }
    public Optional<Wallet> lock(UUID tenantId, UUID id) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where tenant_id=? and id=? for update",
                CustodyHotWalletRepository::map, tenantId, id).stream().findFirst();
    }

    public Optional<Wallet> findByKey(UUID tenantId, String chain, UUID keyId) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where tenant_id=? and chain=? and mpc_key_id=?",
                CustodyHotWalletRepository::map, tenantId, chain, keyId).stream().findFirst();
    }

    public Optional<Wallet> lockByKeyForSigning(UUID tenantId, String chain, UUID keyId) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet where tenant_id=? and chain=? and mpc_key_id=? for share",
                CustodyHotWalletRepository::map, tenantId, chain, keyId).stream().findFirst();
    }

    public int insertStandby(UUID id, UUID tenantId, UUID custodyAddressId, UUID keyId, String chain,
                             String network, String label, UUID createdBy) {
        return jdbc.update("""
                insert into custody_hot_wallet(id,tenant_id,custody_address_id,mpc_key_id,chain,network,label,created_by)
                values (?,?,?,?,?,?,?,?)
                """, id, tenantId, custodyAddressId, keyId, chain, network, label, createdBy);
    }

    public int activate(UUID tenantId, UUID id, UUID keyId, int priority) {
        return jdbc.update("""
                update custody_hot_wallet set status='ACTIVE',route_priority=?,updated_at=now()
                 where tenant_id=? and id=? and mpc_key_id=? and status='STANDBY'
                """, priority, tenantId, id, keyId);
    }

    public int quarantine(UUID tenantId, UUID id, UUID actorId, Instant at, String reason) {
        return jdbc.update("""
                update custody_hot_wallet set status='QUARANTINED', quarantine_actor_id=?,
                    quarantine_at=?, quarantine_reason=?, updated_at=now()
                 where tenant_id=? and id=? and status in ('STANDBY','ACTIVE')
                """, actorId, java.sql.Timestamp.from(at), reason, tenantId, id);
    }
    public int confirmQuarantineFence(UUID tenantId, UUID id, UUID actorId, Instant at) {
        return jdbc.update("""
                update custody_hot_wallet set quarantine_fence_confirmed_at=now(), updated_at=now()
                 where tenant_id=? and id=? and status='QUARANTINED'
                   and quarantine_actor_id=? and quarantine_at=? and quarantine_fence_confirmed_at is null
                """, tenantId, id, actorId, java.sql.Timestamp.from(at));
    }

    private static Wallet map(ResultSet rs, int row) throws SQLException {
        return new Wallet(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class), rs.getObject("mpc_key_id", UUID.class), rs.getString("chain"),
                rs.getString("network"), rs.getString("label"), rs.getString("status"),
                rs.getInt("route_priority"), rs.getObject("quarantine_actor_id", UUID.class),
                instant(rs, "quarantine_at"), rs.getString("quarantine_reason"),
                instant(rs, "quarantine_fence_confirmed_at"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
