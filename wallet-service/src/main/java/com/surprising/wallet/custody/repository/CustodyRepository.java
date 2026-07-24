package com.surprising.wallet.custody.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class CustodyRepository {
    private final JdbcTemplate jdbc;    public CustodyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord createTenant(UUID tenantId, String slug, String name, UUID adminId,
                                     String adminEmail, String adminDisplayName, String passwordHash) {
        jdbc.update("""
                        insert into custody_tenant(id, slug, name)
                        values (?, ?, ?)
                        """, tenantId, slug, name);
        jdbc.update("""
                        insert into custody_tenant_user(
                            id, tenant_id, email, display_name, password_hash, role, status)
                        values (?, ?, ?, ?, ?, 'TENANT_ADMIN', 'ACTIVE')
                        """, adminId, tenantId, adminEmail, adminDisplayName, passwordHash);
        return requireTenant(tenantId);
    }
    public Optional<TenantRecord> findTenantBySlug(String slug) {
        return jdbc.query("""
                        select id, slug, name, status, derivation_namespace, ip_allowlist_enabled,
                               display_currency, created_at, updated_at
                          from custody_tenant
                         where slug = ?
                        """, (rs, rowNum) -> new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getInt("derivation_namespace"),
                        rs.getBoolean("ip_allowlist_enabled"),
                        rs.getString("display_currency"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                slug).stream().findFirst();
    }
    public TenantRecord requireTenant(UUID tenantId) {
        return jdbc.query("""
                        select id, slug, name, status, derivation_namespace, ip_allowlist_enabled,
                               display_currency, created_at, updated_at
                          from custody_tenant
                         where id = ?
                        """, (rs, rowNum) -> new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getInt("derivation_namespace"),
                        rs.getBoolean("ip_allowlist_enabled"),
                        rs.getString("display_currency"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenantId).stream().findFirst().orElseThrow(() ->
                new IllegalArgumentException("tenant not found"));
    }

    public List<Map<String, Object>> listTenants(
            String search, String status, int limit, int offset) {
        return jdbc.query("""
                        select t.id, t.slug, t.name, t.status, t.derivation_namespace,
                               t.ip_allowlist_enabled, t.display_currency, t.created_at, t.updated_at,
                               coalesce(a.address_count, 0) as address_count,
                               coalesce(d.deposit_count, 0) as deposit_count,
                               coalesce(w.withdrawal_count, 0) as withdrawal_count,
                               coalesce(e.active_webhook_count, 0) as active_webhook_count,
                               coalesce(k.active_api_key_count, 0) as active_api_key_count,
                               coalesce(g.gas_account_count, 0) as gas_account_count,
                               coalesce(f.failed_webhook_delivery_count, 0)
                                   as failed_webhook_delivery_count
                          from custody_tenant t
                          left join (
                              select a.tenant_id, count(*) as address_count
                                from custody_address a
                               where not exists (
                                   select 1 from custody_gas_account g
                                    where g.custody_address_id = a.id
                               )
                               group by a.tenant_id
                          ) a on a.tenant_id = t.id
                          left join (
                              select d.tenant_id, count(*) as deposit_count
                                from custody_deposit d
                               where not exists (
                                   select 1 from custody_gas_account g
                                    where g.custody_address_id = d.custody_address_id
                               )
                               group by d.tenant_id
                          ) d on d.tenant_id = t.id
                          left join (
                              select tenant_id, count(*) as withdrawal_count
                                from custody_withdrawal group by tenant_id
                          ) w on w.tenant_id = t.id
                          left join (
                              select tenant_id, count(*) as active_webhook_count
                                from custody_webhook_endpoint
                               where status = 'ACTIVE'
                               group by tenant_id
                          ) e on e.tenant_id = t.id
                          left join (
                              select tenant_id, count(*) as active_api_key_count
                                from custody_api_key
                               where status = 'ACTIVE'
                               group by tenant_id
                          ) k on k.tenant_id = t.id
                          left join (
                              select tenant_id, count(*) as gas_account_count
                                from custody_gas_account
                               where status = 'ACTIVE'
                               group by tenant_id
                          ) g on g.tenant_id = t.id
                          left join (
                              select tenant_id, count(*) as failed_webhook_delivery_count
                                from custody_webhook_delivery
                               where status = 'FAILED'
                               group by tenant_id
                          ) f on f.tenant_id = t.id
                         cross join (
                             select ?::varchar as search, ?::varchar as status
                         ) filters
                         where (
                             filters.search = ''
                             or t.slug ilike '%' || filters.search || '%'
                             or t.name ilike '%' || filters.search || '%'
                         )
                           and (filters.status = '' or t.status = filters.status)
                         order by t.created_at desc, t.id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("slug", rs.getString("slug"));
                    row.put("name", rs.getString("name"));
                    row.put("status", rs.getString("status"));
                    row.put("derivationNamespace", rs.getInt("derivation_namespace"));
                    row.put("ipAllowlistEnabled", rs.getBoolean("ip_allowlist_enabled"));
                    row.put("displayCurrency", rs.getString("display_currency"));
                    row.put("addressCount", rs.getLong("address_count"));
                    row.put("depositCount", rs.getLong("deposit_count"));
                    row.put("withdrawalCount", rs.getLong("withdrawal_count"));
                    row.put("activeWebhookCount", rs.getLong("active_webhook_count"));
                    row.put("activeApiKeyCount", rs.getLong("active_api_key_count"));
                    row.put("gasAccountCount", rs.getLong("gas_account_count"));
                    row.put("failedWebhookDeliveryCount",
                            rs.getLong("failed_webhook_delivery_count"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, blankToEmpty(search), blankToEmpty(status),
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public long countTenants(String search, String status) {
        Long count = jdbc.queryForObject("""
                select count(*)
                  from custody_tenant t
                 where (
                     ? = ''
                     or t.slug ilike '%' || ? || '%'
                     or t.name ilike '%' || ? || '%'
                 )
                   and (? = '' or t.status = ?)
                """, Long.class,
                blankToEmpty(search), blankToEmpty(search), blankToEmpty(search),
                blankToEmpty(status), blankToEmpty(status));
        return count == null ? 0L : count;
    }
    public Map<String, Object> tenantOperationsSummary(UUID tenantId) {
        return jdbc.query("""
                        select
                            (select count(*) from custody_address a
                              where a.tenant_id = t.id
                                and not exists (
                                    select 1 from custody_gas_account g
                                     where g.custody_address_id = a.id
                                )) as address_count,
                            (select count(*) from custody_deposit d
                              where d.tenant_id = t.id
                                and not exists (
                                    select 1 from custody_gas_account g
                                     where g.custody_address_id = d.custody_address_id
                                )) as deposit_count,
                            (select count(*) from custody_withdrawal w
                              where w.tenant_id = t.id) as withdrawal_count,
                            (select count(*) from custody_api_key k
                              where k.tenant_id = t.id and k.status = 'ACTIVE')
                                as active_api_key_count,
                            (select count(*) from custody_webhook_endpoint e
                              where e.tenant_id = t.id and e.status = 'ACTIVE')
                                as active_webhook_count,
                            (select count(*) from custody_gas_account g
                              where g.tenant_id = t.id and g.status = 'ACTIVE')
                                as gas_account_count,
                            (select count(*) from custody_webhook_delivery d
                              where d.tenant_id = t.id and d.status = 'FAILED')
                                as failed_webhook_delivery_count,
                            (select count(*) from custody_tenant_user u
                              where u.tenant_id = t.id) as user_count,
                            (select count(*) from custody_session s
                              where s.tenant_id = t.id and s.revoked_at is null
                                and s.expires_at > now()) as active_session_count
                          from custody_tenant t
                         where t.id = ?
                        """, rs -> {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("tenant not found");
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("addressCount", rs.getLong("address_count"));
                    row.put("depositCount", rs.getLong("deposit_count"));
                    row.put("withdrawalCount", rs.getLong("withdrawal_count"));
                    row.put("activeApiKeyCount", rs.getLong("active_api_key_count"));
                    row.put("activeWebhookCount", rs.getLong("active_webhook_count"));
                    row.put("gasAccountCount", rs.getLong("gas_account_count"));
                    row.put("failedWebhookDeliveryCount",
                            rs.getLong("failed_webhook_delivery_count"));
                    row.put("userCount", rs.getLong("user_count"));
                    row.put("activeSessionCount", rs.getLong("active_session_count"));
                    return row;
                }, tenantId);
    }
    public void updateTenantProfile(UUID tenantId, String name, String displayCurrency) {
        if (jdbc.update("""
                        update custody_tenant
                           set name = ?, display_currency = ?, updated_at = now()
                         where id = ?
                        """, name, displayCurrency, tenantId) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
    }
    public void updateTenantStatus(UUID tenantId, String status) {
        if (jdbc.update("""
                        update custody_tenant
                           set status = ?, updated_at = now()
                         where id = ?
                        """, status, tenantId) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
    }
    public int revokeTenantSessions(UUID tenantId) {
        return jdbc.update("""
                update custody_session
                   set revoked_at = now()
                 where tenant_id = ? and revoked_at is null
                """, tenantId);
    }
    public Optional<AuthUser> findTenantUser(String email) {
        return jdbc.query("""
                        select u.id, u.tenant_id, t.slug as tenant_slug, t.status as tenant_status,
                               u.email, u.display_name, u.password_hash, u.role, u.status,
                               u.failed_login_count, u.locked_until
                          from custody_tenant_user u
                          join custody_tenant t on t.id = u.tenant_id
                         where u.tenant_id is not null and lower(u.email) = lower(?)
                        """, (rs, rowNum) -> mapAuthUser(rs), email).stream().findFirst();
    }
    public Optional<AuthUser> findPlatformUser(String email) {
        return jdbc.query("""
                        select u.id, u.tenant_id, null::varchar as tenant_slug,
                               'ACTIVE'::varchar as tenant_status, u.email, u.display_name,
                               u.password_hash, u.role, u.status, u.failed_login_count, u.locked_until
                          from custody_tenant_user u
                         where u.tenant_id is null
                           and u.role = 'PLATFORM_ADMIN'
                           and lower(u.email) = lower(?)
                        """, (rs, rowNum) -> mapAuthUser(rs), email).stream().findFirst();
    }
    public boolean platformAdminExists() {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_tenant_user
                 where tenant_id is null and role = 'PLATFORM_ADMIN'
                """, Long.class);
        return count != null && count > 0;
    }
    public void insertPlatformAdmin(UUID userId, String email, String passwordHash) {
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, null, ?, 'Platform administrator', ?, 'PLATFORM_ADMIN', 'ACTIVE')
                on conflict do nothing
                """, userId, email.toLowerCase(Locale.ROOT), passwordHash);
    }
    public void recordLoginFailure(UUID userId, Instant lockedUntil) {
        jdbc.update("""
                        update custody_tenant_user
                           set failed_login_count = failed_login_count + 1,
                               locked_until = ?,
                               updated_at = now()
                         where id = ?
                        """, timestampOrNull(lockedUntil), userId);
    }
    public void recordLoginSuccess(UUID userId) {
        jdbc.update("""
                        update custody_tenant_user
                           set failed_login_count = 0, locked_until = null,
                               last_login_at = now(), updated_at = now()
                         where id = ?
                        """, userId);
    }

    public void insertSession(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                              String sourceIp, String userAgent, Instant expiresAt) {
        jdbc.update("""
                        insert into custody_session(
                            id, tenant_user_id, tenant_id, token_hash, source_ip, user_agent, expires_at)
                        values (?, ?, ?, ?, cast(nullif(?, '') as inet), ?, ?)
                        """, sessionId, userId, tenantId, tokenHash, sourceIp, truncate(userAgent, 512),
                Timestamp.from(expiresAt));
    }
    public Optional<SessionRecord> findActiveSession(String tokenHash) {
        return jdbc.query("""
                        select s.id as session_id, s.tenant_user_id, s.tenant_id, t.slug as tenant_slug,
                               u.email, u.display_name, u.role, u.status as user_status,
                               coalesce(t.status, 'ACTIVE') as tenant_status, s.expires_at
                          from custody_session s
                          join custody_tenant_user u on u.id = s.tenant_user_id
                          left join custody_tenant t on t.id = s.tenant_id
                         where s.token_hash = ?
                           and s.revoked_at is null
                           and s.expires_at > now()
                        """, (rs, rowNum) -> new SessionRecord(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("tenant_user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getString("user_status"),
                        rs.getString("tenant_status"),
                        rs.getTimestamp("expires_at").toInstant()),
                tokenHash).stream().findFirst();
    }
    public List<Map<String, Object>> listTenantUsers(UUID tenantId) {
        return jdbc.query("""
                        select id, email, display_name, role, status, failed_login_count,
                               locked_until, last_login_at, created_at, updated_at
                          from custody_tenant_user
                         where tenant_id = ?
                         order by case role
                                      when 'TENANT_ADMIN' then 0
                                      when 'OPERATOR' then 1
                                      else 2
                                  end,
                                  created_at,
                                  id
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("email", rs.getString("email"));
                    row.put("displayName", rs.getString("display_name"));
                    row.put("role", rs.getString("role"));
                    row.put("status", rs.getString("status"));
                    row.put("failedLoginCount", rs.getInt("failed_login_count"));
                    row.put("lockedUntil", instantOrNull(rs.getTimestamp("locked_until")));
                    row.put("lastLoginAt", instantOrNull(rs.getTimestamp("last_login_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId);
    }
    public Map<String, Object> unlockTenantAdministrator(UUID tenantId, UUID userId) {
        if (jdbc.update("""
                        update custody_tenant_user
                           set failed_login_count = 0, locked_until = null, updated_at = now()
                         where tenant_id = ? and id = ?
                           and role = 'TENANT_ADMIN' and status = 'ACTIVE'
                        """, tenantId, userId) != 1) {
            throw new IllegalArgumentException("active tenant administrator not found");
        }
        return listTenantUsers(tenantId).stream()
                .filter(user -> userId.equals(user.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "tenant administrator not found"));
    }
    public void touchSession(UUID sessionId) {
        jdbc.update("""
                        update custody_session
                           set last_seen_at = now()
                         where id = ?
                           and last_seen_at < now() - interval '5 minutes'
                        """, sessionId);
    }
    public void revokeSession(String tokenHash) {
        jdbc.update("""
                update custody_session set revoked_at = now()
                 where token_hash = ? and revoked_at is null
                """, tokenHash);
    }

    public ApiKeyRecord insertApiKey(UUID id, UUID tenantId, String keyId, String name,
                                     String encryptedSecret, UUID createdBy) {
        jdbc.update("""
                        insert into custody_api_key(
                            id, tenant_id, key_id, name, secret_ciphertext, created_by)
                        values (?, ?, ?, ?, ?, ?)
                        """, id, tenantId, keyId, name, encryptedSecret, createdBy);
        return requireApiKey(keyId);
    }
    public Optional<ApiKeyRecord> findActiveApiKey(String keyId) {
        return jdbc.query("""
                        select k.id, k.tenant_id, t.slug as tenant_slug, t.status as tenant_status,
                               t.ip_allowlist_enabled, k.key_id, k.name, k.secret_ciphertext,
                               k.status, k.expires_at, k.created_at
                          from custody_api_key k
                          join custody_tenant t on t.id = k.tenant_id
                         where k.key_id = ?
                           and k.status = 'ACTIVE'
                           and (k.expires_at is null or k.expires_at > now())
                        """, (rs, rowNum) -> mapApiKey(rs), keyId).stream().findFirst();
    }
    public ApiKeyRecord requireApiKey(String keyId) {
        return jdbc.query("""
                        select k.id, k.tenant_id, t.slug as tenant_slug, t.status as tenant_status,
                               t.ip_allowlist_enabled, k.key_id, k.name, k.secret_ciphertext,
                               k.status, k.expires_at, k.created_at
                          from custody_api_key k
                          join custody_tenant t on t.id = k.tenant_id
                         where k.key_id = ?
                        """, (rs, rowNum) -> mapApiKey(rs), keyId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("API key not found"));
    }
    public List<Map<String, Object>> listApiKeys(UUID tenantId) {
        return jdbc.query("""
                        select id, key_id, name, status, last_used_at, last_used_ip,
                               expires_at, created_at, revoked_at
                          from custody_api_key
                         where tenant_id = ?
                         order by created_at desc
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("keyId", rs.getString("key_id"));
                    row.put("name", rs.getString("name"));
                    row.put("status", rs.getString("status"));
                    row.put("lastUsedAt", instantOrNull(rs.getTimestamp("last_used_at")));
                    row.put("lastUsedIp", rs.getString("last_used_ip"));
                    row.put("expiresAt", instantOrNull(rs.getTimestamp("expires_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("revokedAt", instantOrNull(rs.getTimestamp("revoked_at")));
                    return row;
                }, tenantId);
    }
    public void revokeApiKey(UUID tenantId, UUID keyId) {
        if (jdbc.update("""
                        update custody_api_key
                           set status = 'REVOKED', revoked_at = now()
                         where tenant_id = ? and id = ? and status = 'ACTIVE'
                        """, tenantId, keyId) != 1) {
            throw new IllegalArgumentException("active API key not found");
        }
    }
    public void touchApiKey(UUID keyId, String sourceIp) {
        jdbc.update("""
                        update custody_api_key
                           set last_used_at = now(), last_used_ip = cast(nullif(?, '') as inet)
                         where id = ?
                           and (
                               last_used_at is null
                               or last_used_at < now() - interval '1 minute'
                               or last_used_ip is distinct from cast(nullif(?, '') as inet)
                           )
                        """, sourceIp, keyId, sourceIp);
    }
    public boolean reserveNonce(String keyId, String nonce, Instant expiresAt) {
        try {
            return jdbc.update("""
                    insert into custody_api_nonce(key_id, nonce, expires_at)
                    values (?, ?, ?)
                    """, keyId, nonce, Timestamp.from(expiresAt)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
    public List<String> activeIpRules(UUID tenantId) {
        return jdbc.query("""
                select cidr::text from custody_ip_rule
                 where tenant_id = ? and enabled = true
                 order by cidr
                """, (rs, rowNum) -> rs.getString(1), tenantId);
    }
    public List<Map<String, Object>> listIpRules(UUID tenantId) {
        return jdbc.query("""
                        select id, label, cidr::text as cidr, enabled, created_at, updated_at
                          from custody_ip_rule
                         where tenant_id = ?
                         order by created_at desc
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("label", rs.getString("label"));
                    row.put("cidr", rs.getString("cidr"));
                    row.put("enabled", rs.getBoolean("enabled"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId);
    }
    public Map<String, Object> insertIpRule(UUID tenantId, UUID ruleId, String label, String cidr, UUID createdBy) {
        return jdbc.queryForMap("""
                        insert into custody_ip_rule(id, tenant_id, label, cidr, created_by)
                        values (?, ?, ?, cast(? as inet), ?)
                        returning id, label, cidr::text as cidr, enabled, created_at, updated_at
                        """, ruleId, tenantId, label, cidr, createdBy);
    }
    public void deleteIpRule(UUID tenantId, UUID ruleId) {
        if (jdbc.update("delete from custody_ip_rule where tenant_id = ? and id = ?", tenantId, ruleId) != 1) {
            throw new IllegalArgumentException("IP rule not found");
        }
    }
    public void setIpAllowlistEnabled(UUID tenantId, boolean enabled) {
        if (enabled && activeIpRules(tenantId).isEmpty()) {
            throw new IllegalStateException("add at least one enabled IP rule before enforcing the allowlist");
        }
        if (jdbc.update("""
                update custody_tenant
                   set ip_allowlist_enabled = ?, updated_at = now()
                 where id = ?
                """, enabled, tenantId) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
    }
    public int resolveDerivationSubject(UUID tenantId, String subject) {
        String mappingKey = tenantId + "\n" + subject;
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null,
                mappingKey);
        Integer existing = jdbc.query("""
                        select derivation_subject
                          from custody_derivation_subject
                         where tenant_id = ? and subject = ?
                        """, rs -> rs.next() ? rs.getInt(1) : null, tenantId, subject);
        if (existing != null) {
            return existing;
        }
        Integer allocated = jdbc.queryForObject("""
                        insert into custody_derivation_subject(tenant_id, subject)
                        values (?, ?)
                        returning derivation_subject
                        """, Integer.class, tenantId, subject);
        if (allocated == null || allocated <= 0) {
            throw new IllegalStateException("failed to allocate custody derivation subject");
        }
        return allocated;
    }
    public void lockSubjectAddressAllocation(UUID tenantId, String chain, String subject) {
        String allocationKey = tenantId + "\n" + chain + "\n" + subject;
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 1))",
                rs -> null,
                allocationKey);
    }

    public AddressRecord insertAddress(UUID id, UUID tenantId, long chainAddressId, String chain,
                                       String network, String address, String memo,
                                       String subject, String label, String metadataJson,
                                       String source, int derivationSubject, long addressVersion,
                                       long derivationChild,
                                       UUID createdBy) {
        jdbc.update("""
                        insert into custody_address(
                            id, tenant_id, chain_address_id, chain, network, address, memo,
                            subject, label, metadata, source, derivation_subject,
                            address_version, derivation_child, created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)
                        """, id, tenantId, chainAddressId, chain, network, address, memo,
                subject, label, metadataJson, source, derivationSubject, addressVersion,
                derivationChild, createdBy);
        return requireAddress(tenantId, id);
    }
    public void assignChainAddressTenant(UUID tenantId, long chainAddressId) {
        if (jdbc.update("""
                        update chain_address
                           set tenant_id = ?, updated_at = now()
                         where id = ? and (tenant_id is null or tenant_id = ?)
                        """, tenantId, chainAddressId, tenantId) != 1) {
            throw new IllegalStateException("chain address belongs to another tenant");
        }
    }
    public AddressRecord requireAddress(UUID tenantId, UUID addressId) {
        return jdbc.query("""
                        select id, tenant_id, chain_address_id, chain, network, address, memo,
                               subject, label, metadata::text as metadata, source,
                               status, derivation_subject, address_version, derivation_child,
                               created_at, updated_at
                          from custody_address
                         where tenant_id = ? and id = ?
                        """, (rs, rowNum) -> mapAddress(rs), tenantId, addressId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("custody address not found"));
    }

    public Optional<AddressRecord> findAddressBySubjectAndVersion(
            UUID tenantId, String chain, String subject, long addressVersion) {
        return jdbc.query("""
                        select id, tenant_id, chain_address_id, chain, network, address, memo,
                               subject, label, metadata::text as metadata, source,
                               status, derivation_subject, address_version, derivation_child,
                               created_at, updated_at
                          from custody_address
                         where tenant_id = ? and chain = ? and subject = ?
                           and address_version = ?
                        """, (rs, rowNum) -> mapAddress(rs),
                tenantId, chain, subject, addressVersion)
                .stream().findFirst();
    }
    public boolean isGasAddress(UUID tenantId, UUID addressId) {
        Boolean result = jdbc.queryForObject("""
                        select exists (
                            select 1 from custody_gas_account
                             where tenant_id = ? and custody_address_id = ?
                        )
                        """, Boolean.class, tenantId, addressId);
        return Boolean.TRUE.equals(result);
    }

    public boolean hasOpenReorgDeficit(UUID tenantId, UUID custodyAddressId,
                                       String chain, String assetSymbol) {
        Boolean result = jdbc.queryForObject("""
                        select exists (
                            select 1
                              from custody_reorg_deficit deficit
                              join custody_address custody
                                on custody.tenant_id = deficit.tenant_id
                               and custody.id = ?
                              join chain_address address
                                on address.tenant_id = custody.tenant_id
                               and address.id = custody.chain_address_id
                             where deficit.tenant_id = ? and deficit.chain = ?
                               and deficit.asset_symbol = ? and deficit.status = 'OPEN'
                               and deficit.account_id = address.account_id
                               and deficit.recovered_amount < deficit.deficit_amount
                        )
                        """, Boolean.class, custodyAddressId, tenantId, chain, assetSymbol);
        return Boolean.TRUE.equals(result);
    }

    public AddressRecord updateAddress(UUID tenantId, UUID addressId, String label,
                                       String metadataJson, String status) {
        if (jdbc.update("""
                        update custody_address
                           set label = ?,
                               metadata = cast(? as jsonb),
                               status = ?,
                               updated_at = now()
                         where tenant_id = ? and id = ?
                        """, label, metadataJson, status, tenantId, addressId) != 1) {
            throw new IllegalArgumentException("custody address not found");
        }
        return requireAddress(tenantId, addressId);
    }

    public List<AddressRecord> listAddresses(UUID tenantId, String chain, String source,
                                             String status, String search, int limit, int offset) {
        String normalizedSearch = search == null ? "" : search.trim();
        return jdbc.query("""
                        select id, tenant_id, chain_address_id, chain, network, address, memo,
                               subject, label, metadata::text as metadata, source,
                               status, derivation_subject, address_version, derivation_child,
                               created_at, updated_at
                          from custody_address
                         where tenant_id = ?
                           and not exists (
                               select 1 from custody_gas_account g
                                where g.custody_address_id = custody_address.id
                           )
                           and (? = '' or chain = ?)
                           and (? = '' or source = ?)
                           and (? = '' or status = ?)
                           and (? = '' or address ilike '%' || ? || '%'
                                or subject ilike '%' || ? || '%'
                                or coalesce(label, '') ilike '%' || ? || '%')
                         order by created_at desc, id
                         limit ? offset ?
                        """, (rs, rowNum) -> mapAddress(rs),
                tenantId,
                blankToEmpty(chain), blankToEmpty(chain),
                blankToEmpty(source), blankToEmpty(source),
                blankToEmpty(status), blankToEmpty(status),
                normalizedSearch, normalizedSearch, normalizedSearch, normalizedSearch,
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public List<Map<String, Object>> tenantAssetOverview(UUID tenantId) {
        return jdbc.query("""
                        with tenant_accounts as (
                            select distinct c.id as custody_address_id, c.tenant_id,
                                   related.chain, related.account_id
                              from custody_address c
                              join chain_address base
                                on base.tenant_id = c.tenant_id
                               and base.id = c.chain_address_id
                              join chain_address related
                                on related.tenant_id = c.tenant_id
                               and related.chain = base.chain
                               and related.user_id = base.user_id
                               and related.biz = base.biz
                               and related.address_index = base.address_index
                               and related.wallet_role = base.wallet_role
                               and related.enabled = true
                             where c.tenant_id = ?
                               and not exists (
                                   select 1 from custody_gas_account g
                                    where g.tenant_id = c.tenant_id
                                      and g.custody_address_id = c.id
                               )
                            union
                            select distinct c.id, c.tenant_id, base.chain, base.account_id
                              from custody_address c
                              join chain_address base
                                on base.tenant_id = c.tenant_id
                               and base.id = c.chain_address_id
                             where c.tenant_id = ?
                               and not exists (
                                   select 1 from custody_gas_account g
                                    where g.tenant_id = c.tenant_id
                                      and g.custody_address_id = c.id
                               )
                        )
                        select lb.chain, lb.asset_symbol,
                               coalesce(sum(lb.available_balance), 0) as available_balance,
                               coalesce(sum(lb.locked_balance), 0) as locked_balance,
                               coalesce(sum(lb.total_balance), 0) as total_balance,
                               count(distinct ta.custody_address_id) as address_count
                          from tenant_accounts ta
                          join ledger_balance lb
                            on lb.tenant_id = ta.tenant_id
                           and lb.chain = ta.chain
                           and lower(lb.account_id) = lower(ta.account_id)
                         group by lb.chain, lb.asset_symbol
                         order by lb.asset_symbol, lb.chain
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("chain", rs.getString("chain"));
                    row.put("assetSymbol", rs.getString("asset_symbol"));
                    row.put("availableBalance", rs.getBigDecimal("available_balance"));
                    row.put("lockedBalance", rs.getBigDecimal("locked_balance"));
                    row.put("totalBalance", rs.getBigDecimal("total_balance"));
                    row.put("addressCount", rs.getLong("address_count"));
                    return row;
                }, tenantId, tenantId);
    }
    public Optional<GasAccountRecord> findGasAccount(UUID tenantId, String chain) {
        return gasAccounts(tenantId, chain).stream().findFirst();
    }

    public GasAccountRecord insertGasAccount(
            UUID id, UUID tenantId, UUID custodyAddressId, String chain, String network,
            String nativeSymbol, java.math.BigDecimal lowBalanceThreshold, UUID createdBy) {
        jdbc.update("""
                        insert into custody_gas_account(
                            id, tenant_id, custody_address_id, chain, network, native_symbol,
                            low_balance_threshold, created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (tenant_id, chain) do nothing
                        """, id, tenantId, custodyAddressId, chain, network, nativeSymbol,
                lowBalanceThreshold, createdBy);
        return findGasAccount(tenantId, chain)
                .orElseThrow(() -> new IllegalStateException("failed to create gas account"));
    }
    public GasAccountRecord requireGasAccount(UUID tenantId, UUID gasAccountId) {
        return gasAccounts(tenantId, "").stream()
                .filter(account -> account.id().equals(gasAccountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("gas account not found"));
    }
    public List<GasAccountRecord> listGasAccounts(UUID tenantId) {
        return gasAccounts(tenantId, "");
    }

    public GasAccountRecord updateGasAccount(
            UUID tenantId, UUID gasAccountId, java.math.BigDecimal lowBalanceThreshold,
            String status) {
        if (jdbc.update("""
                        update custody_gas_account
                           set low_balance_threshold = ?, status = ?, updated_at = now()
                         where tenant_id = ? and id = ?
                        """, lowBalanceThreshold, status, tenantId, gasAccountId) != 1) {
            throw new IllegalArgumentException("gas account not found");
        }
        return requireGasAccount(tenantId, gasAccountId);
    }

    public List<Map<String, Object>> listGasTopups(
            UUID tenantId, UUID gasAccountId, int limit, int offset) {
        return jdbc.query("""
                        select d.id, d.chain, d.asset_symbol, d.tx_hash, d.log_index,
                               d.amount, d.status, d.credited_at, d.created_at
                          from custody_deposit d
                          join custody_gas_account g
                            on g.custody_address_id = d.custody_address_id
                           and g.tenant_id = d.tenant_id
                         where d.tenant_id = ? and g.id = ?
                         order by d.created_at desc, d.id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("chain", rs.getString("chain"));
                    row.put("assetSymbol", rs.getString("asset_symbol"));
                    row.put("txHash", rs.getString("tx_hash"));
                    row.put("logIndex", rs.getLong("log_index"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("status", rs.getString("status"));
                    row.put("creditedAt", instantOrNull(rs.getTimestamp("credited_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    return row;
                }, tenantId, gasAccountId,
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public GasPricingMetadata gasPricingMetadata(String chain, String assetSymbol) {
        return jdbc.query("""
                        select p.family, p.native_symbol, coalesce(p.default_fee_rate, 1) default_fee_rate,
                               native_asset.decimals,
                               coalesce(requested.native_asset, false) requested_native
                          from chain_profile p
                          join chain_asset native_asset
                            on native_asset.chain = p.chain
                           and native_asset.symbol = p.native_symbol
                           and native_asset.native_asset = true
                           and native_asset.active = true
                          left join chain_asset requested
                            on requested.chain = p.chain
                           and requested.symbol = ?
                           and requested.active = true
                         where p.chain = ? and p.enabled = true
                         order by p.id
                         limit 1
                        """, (rs, rowNum) -> new GasPricingMetadata(
                        rs.getString("family"),
                        rs.getString("native_symbol"),
                        rs.getLong("default_fee_rate"),
                        rs.getInt("decimals"),
                        rs.getBoolean("requested_native")),
                assetSymbol, chain).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "enabled chain gas pricing is unavailable for " + chain));
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord reserveGasUsage(
            UUID tenantId, UUID custodyWithdrawalId, String orderNo, String chain,
            java.math.BigDecimal reservedAmount) {
        return reserveGasUsage(tenantId, "WITHDRAWAL", custodyWithdrawalId,
                orderNo, chain, reservedAmount);
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord reserveGasUsage(
            UUID tenantId, String operationType, UUID operationId, String referenceNo,
            String chain, java.math.BigDecimal reservedAmount) {
        requireGasOperation(tenantId, operationType, operationId, chain);
        GasAccountRecord account = findGasAccount(tenantId, chain)
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(() -> new IllegalStateException(
                        "set up an active " + chain + " gas account before creating withdrawals"));
        GasFundingSource funding = gasFundingSource(account);
        if (jdbc.update("""
                        update ledger_balance
                           set available_balance = available_balance - ?,
                               locked_balance = locked_balance + ?,
                               updated_at = now()
                         where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                           and tenant_id = ?
                           and available_balance >= ?
                           and not exists (
                               select 1 from custody_gas_usage u
                                where u.tenant_id = ? and u.gas_account_id = ?
                                  and u.status = 'OVERDUE'
                           )
                        """, reservedAmount, reservedAmount, chain, account.nativeSymbol(),
                funding.accountId(), tenantId, reservedAmount, tenantId, account.id()) != 1) {
            throw new IllegalStateException(
                    "insufficient " + account.nativeSymbol()
                            + " balance for network fees");
        }
        UUID usageId = UUID.randomUUID();
        jdbc.update("""
                        insert into custody_gas_usage(
                            id, tenant_id, gas_account_id, operation_type,
                            operation_id, reference_no, chain, native_symbol, reserved_amount)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, usageId, tenantId, account.id(), operationType,
                operationId, referenceNo, chain, account.nativeSymbol(), reservedAmount);
        return requireGasUsage(tenantId, operationType, operationId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord releaseGasUsage(UUID custodyWithdrawalId, String reason) {
        GasUsageRecord usage = requireWithdrawalGasUsage(custodyWithdrawalId);
        return releaseGasUsage(usage.tenantId(), usage.operationType(), usage.operationId(), reason);
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord releaseGasUsage(UUID tenantId, String operationType,
                                          UUID operationId, String reason) {
        GasUsageRecord usage = requireGasUsageForUpdate(tenantId, operationType, operationId);
        if (!"RESERVED".equals(usage.status())) {
            return usage;
        }
        GasAccountRecord account = requireGasAccount(usage.tenantId(), usage.gasAccountId());
        GasFundingSource funding = gasFundingSource(account);
        if (jdbc.update("""
                        update ledger_balance
                           set available_balance = available_balance + ?,
                               locked_balance = locked_balance - ?,
                               updated_at = now()
                         where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                           and tenant_id = ?
                           and locked_balance >= ?
                        """, usage.reservedAmount(), usage.reservedAmount(),
                usage.chain(), usage.nativeSymbol(), funding.accountId(),
                usage.tenantId(), usage.reservedAmount()) != 1) {
            throw new IllegalStateException("gas reservation balance is inconsistent");
        }
        jdbc.update("""
                        update custody_gas_usage
                           set status = 'RELEASED', error_message = ?, updated_at = now(),
                               settled_at = now()
                         where tenant_id = ? and id = ? and status = 'RESERVED'
                        """, reason, usage.tenantId(), usage.id());
        return requireGasUsage(tenantId, operationType, operationId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord settleGasUsage(
            UUID custodyWithdrawalId, java.math.BigDecimal actualAmount,
            String pricingSource, String txHash) {
        GasUsageRecord usage = requireWithdrawalGasUsage(custodyWithdrawalId);
        return settleGasUsage(usage.tenantId(), usage.operationType(), usage.operationId(),
                actualAmount, pricingSource, txHash);
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord settleGasUsage(
            UUID tenantId, String operationType, UUID operationId,
            java.math.BigDecimal actualAmount, String pricingSource, String txHash) {
        GasUsageRecord usage = requireGasUsageForUpdate(tenantId, operationType, operationId);
        if (!Set.of("RESERVED", "OVERDUE").contains(usage.status())) {
            return usage;
        }
        java.math.BigDecimal actual = actualAmount == null || actualAmount.signum() <= 0
                ? usage.reservedAmount()
                : actualAmount.stripTrailingZeros();
        GasAccountRecord account = requireGasAccount(usage.tenantId(), usage.gasAccountId());
        GasFundingSource funding = gasFundingSource(account);
        java.math.BigDecimal difference = usage.reservedAmount().subtract(actual);
        int settled;
        if (difference.signum() >= 0) {
            settled = jdbc.update("""
                            update ledger_balance
                               set available_balance = available_balance + ?,
                                   locked_balance = locked_balance - ?,
                                   total_balance = total_balance - ?,
                                   updated_at = now()
                             where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                               and tenant_id = ?
                               and locked_balance >= ? and total_balance >= ?
                            """, difference, usage.reservedAmount(), actual,
                    usage.chain(), usage.nativeSymbol(), funding.accountId(),
                    usage.tenantId(), usage.reservedAmount(), actual);
        } else {
            java.math.BigDecimal extra = difference.negate();
            settled = jdbc.update("""
                            update ledger_balance
                               set available_balance = available_balance - ?,
                                   locked_balance = locked_balance - ?,
                                   total_balance = total_balance - ?,
                                   updated_at = now()
                             where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                               and tenant_id = ?
                               and available_balance >= ? and locked_balance >= ?
                               and total_balance >= ?
                            """, extra, usage.reservedAmount(), actual,
                    usage.chain(), usage.nativeSymbol(), funding.accountId(),
                    usage.tenantId(), extra, usage.reservedAmount(), actual);
        }
        if (settled != 1) {
            jdbc.update("""
                            update custody_gas_usage
                               set status = 'OVERDUE', actual_amount = ?,
                                   pricing_source = ?, tx_hash = ?,
                                   error_message = 'actual network fee exceeded funded gas balance',
                                   updated_at = now(), settled_at = null
                             where tenant_id = ? and id = ?
                               and status in ('RESERVED', 'OVERDUE')
                            """, actual, pricingSource, txHash, usage.tenantId(), usage.id());
            return requireGasUsage(tenantId, operationType, operationId);
        }
        jdbc.update("""
                        update custody_gas_usage
                           set status = 'SETTLED', actual_amount = ?,
                               pricing_source = ?, tx_hash = ?, error_message = null,
                               updated_at = now(), settled_at = now()
                         where tenant_id = ? and id = ?
                           and status in ('RESERVED', 'OVERDUE')
                        """, actual, pricingSource, txHash, usage.tenantId(), usage.id());
        jdbc.update("""
                        insert into custody_ledger_entry(
                            id, tenant_id, custody_address_id, chain, asset_symbol,
                            account_id, entry_type, direction, amount,
                            reference_type, reference_id)
                        values (?, ?, ?, ?, ?, ?, 'NETWORK_FEE', 'DEBIT', ?,
                                ?, ?)
                        on conflict (tenant_id, entry_type, reference_type, reference_id)
                        do nothing
                        """, UUID.randomUUID(), usage.tenantId(), funding.custodyAddressId(),
                usage.chain(), usage.nativeSymbol(), funding.accountId(), actual,
                usage.operationType(), usage.referenceNo());
        return requireGasUsage(tenantId, operationType, operationId);
    }

    public List<Map<String, Object>> listGasUsage(
            UUID tenantId, UUID gasAccountId, int limit, int offset) {
        return jdbc.query("""
                        select u.id, u.operation_type, u.operation_id, u.reference_no, u.chain,
                               u.native_symbol, u.reserved_amount, u.actual_amount,
                               u.status, u.pricing_source, u.tx_hash, u.error_message,
                               u.created_at, u.updated_at, u.settled_at
                          from custody_gas_usage u
                         where u.tenant_id = ? and u.gas_account_id = ?
                         order by u.created_at desc, u.id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("operationType", rs.getString("operation_type"));
                    row.put("operationId", rs.getObject("operation_id", UUID.class));
                    row.put("referenceNo", rs.getString("reference_no"));
                    row.put("chain", rs.getString("chain"));
                    row.put("nativeSymbol", rs.getString("native_symbol"));
                    row.put("reservedAmount", rs.getBigDecimal("reserved_amount"));
                    row.put("actualAmount", rs.getBigDecimal("actual_amount"));
                    row.put("status", rs.getString("status"));
                    row.put("pricingSource", rs.getString("pricing_source"));
                    row.put("txHash", rs.getString("tx_hash"));
                    row.put("errorMessage", rs.getString("error_message"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    row.put("settledAt", instantOrNull(rs.getTimestamp("settled_at")));
                    return row;
                }, tenantId, gasAccountId,
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public List<GasUsageRecord> listOverdueGasUsage(int limit) {
        return jdbc.query("""
                        select id, tenant_id, gas_account_id, operation_type, operation_id,
                               reference_no, chain, native_symbol, reserved_amount,
                               actual_amount, status, pricing_source, tx_hash,
                               error_message, created_at, updated_at, settled_at
                          from custody_gas_usage
                         where status = 'OVERDUE' and actual_amount is not null
                         order by updated_at, id
                         limit ?
                        """, (rs, rowNum) -> new GasUsageRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("gas_account_id", UUID.class),
                        rs.getString("operation_type"),
                        rs.getObject("operation_id", UUID.class),
                        rs.getString("reference_no"),
                        rs.getString("chain"),
                        rs.getString("native_symbol"),
                        rs.getBigDecimal("reserved_amount"),
                        rs.getBigDecimal("actual_amount"),
                        rs.getString("status"),
                        rs.getString("pricing_source"),
                        rs.getString("tx_hash"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        instantOrNull(rs.getTimestamp("settled_at"))),
                Math.min(Math.max(limit, 1), 200));
    }
    private GasUsageRecord requireGasUsage(UUID tenantId, String operationType, UUID operationId) {
        return findGasUsage(tenantId, operationType, operationId)
                .orElseThrow(() -> new IllegalArgumentException("gas usage not found"));
    }

    private GasUsageRecord requireGasUsageForUpdate(
            UUID tenantId, String operationType, UUID operationId) {
        return jdbc.query("""
                        select id, tenant_id, gas_account_id, operation_type, operation_id,
                               reference_no, chain, native_symbol, reserved_amount,
                               actual_amount, status, pricing_source, tx_hash,
                               error_message, created_at, updated_at, settled_at
                          from custody_gas_usage
                         where tenant_id = ? and operation_type = ? and operation_id = ?
                           for update
                        """, (rs, rowNum) -> new GasUsageRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("gas_account_id", UUID.class),
                        rs.getString("operation_type"),
                        rs.getObject("operation_id", UUID.class),
                        rs.getString("reference_no"),
                        rs.getString("chain"),
                        rs.getString("native_symbol"),
                        rs.getBigDecimal("reserved_amount"),
                        rs.getBigDecimal("actual_amount"),
                        rs.getString("status"),
                        rs.getString("pricing_source"),
                        rs.getString("tx_hash"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        instantOrNull(rs.getTimestamp("settled_at"))),
                tenantId, operationType, operationId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("gas usage not found"));
    }
    public Optional<GasUsageRecord> findGasUsage(UUID custodyWithdrawalId) {
        return jdbc.query("""
                        select tenant_id from custody_gas_usage
                         where operation_type = 'WITHDRAWAL' and operation_id = ?
                        """, (rs, rowNum) -> rs.getObject("tenant_id", UUID.class), custodyWithdrawalId)
                .stream().findFirst()
                .flatMap(tenantId -> findGasUsage(tenantId, "WITHDRAWAL", custodyWithdrawalId));
    }

    public Optional<GasUsageRecord> findGasUsage(
            UUID tenantId, String operationType, UUID operationId) {
        return jdbc.query("""
                        select id, tenant_id, gas_account_id, operation_type, operation_id,
                               reference_no, chain, native_symbol, reserved_amount,
                               actual_amount, status, pricing_source, tx_hash,
                               error_message, created_at, updated_at, settled_at
                          from custody_gas_usage
                         where tenant_id = ? and operation_type = ? and operation_id = ?
                        """, (rs, rowNum) -> new GasUsageRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("gas_account_id", UUID.class),
                        rs.getString("operation_type"),
                        rs.getObject("operation_id", UUID.class),
                        rs.getString("reference_no"),
                        rs.getString("chain"),
                        rs.getString("native_symbol"),
                        rs.getBigDecimal("reserved_amount"),
                        rs.getBigDecimal("actual_amount"),
                        rs.getString("status"),
                        rs.getString("pricing_source"),
                        rs.getString("tx_hash"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        instantOrNull(rs.getTimestamp("settled_at"))),
                tenantId, operationType, operationId).stream().findFirst();
    }
    private GasUsageRecord requireWithdrawalGasUsage(UUID custodyWithdrawalId) {
        return findGasUsage(custodyWithdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("gas usage not found"));
    }

    private void requireGasOperation(UUID tenantId, String operationType,
                                     UUID operationId, String chain) {
        String type = operationType == null ? "" : operationType.trim().toUpperCase(java.util.Locale.ROOT);
        Boolean valid = switch (type) {
            case "WITHDRAWAL" -> jdbc.queryForObject("""
                    select exists(select 1 from custody_withdrawal
                                   where tenant_id = ? and id = ? and chain = ?)
                    """, Boolean.class, tenantId, operationId, chain);
            case "COLLECTION_BATCH" -> jdbc.queryForObject("""
                    select exists(select 1 from evm_collection_batch
                                   where tenant_id = ? and id = ? and chain = ?)
                    """, Boolean.class, tenantId, operationId, chain);
            case "WITHDRAWAL_BATCH" -> jdbc.queryForObject("""
                    select exists(select 1 from evm_withdrawal_batch
                                   where tenant_id = ? and id = ? and chain = ?)
                    """, Boolean.class, tenantId, operationId, chain);
            default -> throw new IllegalArgumentException("unsupported gas operation type");
        };
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException("gas operation does not belong to tenant and chain");
        }
    }
    private static GasFundingSource gasFundingSource(GasAccountRecord gasAccount) {
        return new GasFundingSource(
                gasAccount.custodyAddressId(), gasAccount.accountId());
    }
    private record GasFundingSource(UUID custodyAddressId, String accountId) {
    }

    public Optional<NetworkFee> confirmedNetworkFee(
            String chain, String orderNo, String txHash, int nativeDecimals) {
        if (txHash == null || txHash.isBlank()) {
            return Optional.empty();
        }
        Optional<java.math.BigDecimal> amount;
        String source;
        switch (chain) {
            case "SOLANA" -> {
                amount = atomicFee("""
                        select fee_lamports from sol_transaction
                         where chain = ? and signature = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash, 9);
                source = "CHAIN_CONFIRMED";
            }
            case "APTOS" -> {
                amount = atomicFee("""
                        select gas_used * gas_unit_price from aptos_transaction
                         where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash, 8);
                source = "CHAIN_CONFIRMED";
            }
            case "SUI" -> {
                amount = atomicFee("""
                        select gas_used from sui_transaction
                         where chain = ? and tx_digest = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash, 9);
                source = "CHAIN_CONFIRMED";
            }
            case "TON" -> {
                amount = atomicFee("""
                        select fee_nano from ton_transaction
                         where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash, 9);
                source = "CHAIN_CONFIRMED";
            }
            case "XRP" -> {
                amount = atomicFee("""
                        select fee_drops from xrp_transaction
                         where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash, 6);
                source = "CHAIN_CONFIRMED";
            }
            case "XMR" -> {
                amount = atomicFee("""
                        select fee_atomic from monero_transaction
                         where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                         order by updated_at desc limit 1
                        """, chain, txHash, nativeDecimals);
                source = "CHAIN_CONFIRMED";
            }
            case "NEAR" -> {
                amount = Optional.empty();
                source = "CONFIGURED_RESERVE";
            }
            case "TRON" -> {
                amount = decimalFee("""
                        select fee from tron_tx
                         where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash);
                source = "CHAIN_CONFIRMED";
            }
            default -> {
                amount = decimalFee("""
                        select fee from evm_tx
                         where chain = ? and tx_hash = ? and status = 'CONFIRMED'
                         limit 1
                        """, chain, txHash);
                source = amount.isPresent() ? "CHAIN_RECORDED" : "CONFIGURED_RESERVE";
                if (amount.isEmpty()) {
                    amount = decimalFee("""
                            select fee from withdrawal_order
                             where chain = ? and order_no = ? and status = 'CONFIRMED'
                             limit 1
                            """, chain, orderNo);
                }
            }
        }
        String pricingSource = source;
        return amount.filter(value -> value.signum() > 0)
                .map(value -> new NetworkFee(value.stripTrailingZeros(), pricingSource));
    }

    private Optional<java.math.BigDecimal> atomicFee(
            String sql, String chain, String txHash, int decimals) {
        return decimalFee(sql, chain, txHash)
                .map(value -> value.movePointLeft(decimals));
    }

    private Optional<java.math.BigDecimal> decimalFee(
            String sql, String first, String second) {
        return jdbc.query(sql,
                        (rs, rowNum) -> rs.getBigDecimal(1), first, second)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }
    public Map<String, Object> onboardingStatus(UUID tenantId) {
        return jdbc.queryForObject("""
                        select
                            exists (
                                select 1 from custody_api_key
                                 where tenant_id = t.id and status = 'ACTIVE'
                            ) as has_api_key,
                            exists (
                                select 1 from custody_tenant_chain tc
                                 where tc.tenant_id = t.id and tc.status = 'ACTIVE'
                            ) as has_open_chain,
                            exists (
                                select 1 from custody_webhook_endpoint
                                 where tenant_id = t.id and status = 'ACTIVE'
                                   and verified_at is not null
                            ) as has_verified_webhook,
                            t.ip_allowlist_enabled and exists (
                                select 1 from custody_ip_rule
                                 where tenant_id = t.id and enabled = true
                            ) as has_ip_allowlist,
                            exists (
                                select 1 from custody_address a
                                 where a.tenant_id = t.id
                                   and not exists (
                                       select 1 from custody_gas_account g
                                        where g.custody_address_id = a.id
                                   )
                            ) as has_customer_address,
                            exists (
                                select 1 from custody_gas_account
                                 where tenant_id = t.id and status = 'ACTIVE'
                            ) as has_gas_account,
                            exists (
                                select 1
                                  from custody_gas_account g
                                  join custody_address a
                                    on a.tenant_id = g.tenant_id
                                   and a.id = g.custody_address_id
                                  join chain_address base
                                    on base.tenant_id = a.tenant_id
                                   and base.id = a.chain_address_id
                                  join chain_address related
                                    on related.tenant_id = g.tenant_id
                                   and related.chain = base.chain
                                   and related.user_id = base.user_id
                                   and related.biz = base.biz
                                   and related.address_index = base.address_index
                                   and related.wallet_role = base.wallet_role
                                   and related.asset_symbol = g.native_symbol
                                   and related.enabled = true
                                  join ledger_balance lb
                                    on lb.tenant_id = g.tenant_id
                                   and lb.chain = g.chain
                                   and lb.asset_symbol = g.native_symbol
                                   and lower(lb.account_id) = lower(related.account_id)
                                 where g.tenant_id = t.id
                                   and g.status = 'ACTIVE'
                                   and lb.available_balance > 0
                            ) as has_funded_gas
                          from custody_tenant t
                         where t.id = ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    boolean apiKey = rs.getBoolean("has_api_key");
                    boolean openChain = rs.getBoolean("has_open_chain");
                    boolean webhook = rs.getBoolean("has_verified_webhook");
                    boolean allowlist = rs.getBoolean("has_ip_allowlist");
                    boolean address = rs.getBoolean("has_customer_address");
                    boolean gasAccount = rs.getBoolean("has_gas_account");
                    boolean fundedGas = rs.getBoolean("has_funded_gas");
                    result.put("apiKeyConfigured", apiKey);
                    result.put("chainOpened", openChain);
                    result.put("webhookConfigured", webhook);
                    result.put("ipAllowlistConfigured", allowlist);
                    result.put("addressCreated", address);
                    result.put("gasAccountConfigured", gasAccount);
                    result.put("gasAccountFunded", fundedGas);
                    result.put("completedSteps", List.of(
                            openChain, apiKey, webhook, allowlist, address, gasAccount, fundedGas)
                            .stream().filter(Boolean::booleanValue).count());
                    result.put("totalSteps", 7);
                    result.put("ready", openChain && apiKey && webhook && allowlist && address
                            && gasAccount && fundedGas);
                    return result;
                }, tenantId);
    }
    private List<GasAccountRecord> gasAccounts(UUID tenantId, String chain) {
        return jdbc.query("""
                        select g.id, g.tenant_id, g.custody_address_id, g.chain, g.network,
                               g.native_symbol, g.low_balance_threshold, g.status,
                               a.address, a.memo, a.derivation_child, base.account_id,
                               coalesce(b.available_balance, 0) as available_balance,
                               coalesce(b.locked_balance, 0) as locked_balance,
                               coalesce(b.total_balance, 0) as total_balance,
                               g.created_at, g.updated_at
                          from custody_gas_account g
                          join custody_address a
                            on a.tenant_id = g.tenant_id
                           and a.id = g.custody_address_id
                          join chain_address base
                            on base.tenant_id = a.tenant_id
                           and base.id = a.chain_address_id
                          left join lateral (
                              select coalesce(sum(lb.available_balance), 0) as available_balance,
                                     coalesce(sum(lb.locked_balance), 0) as locked_balance,
                                     coalesce(sum(lb.total_balance), 0) as total_balance
                                from (
                                    select distinct related.account_id
                                      from chain_address related
                                     where related.tenant_id = g.tenant_id
                                       and related.chain = base.chain
                                       and related.user_id = base.user_id
                                       and related.biz = base.biz
                                       and related.address_index = base.address_index
                                       and related.wallet_role = base.wallet_role
                                       and related.asset_symbol = g.native_symbol
                                       and related.enabled = true
                                ) account
                                join ledger_balance lb
                                  on lb.tenant_id = g.tenant_id
                                 and lb.chain = g.chain
                                 and lb.asset_symbol = g.native_symbol
                                 and lower(lb.account_id) = lower(account.account_id)
                          ) b on true
                         where g.tenant_id = ?
                           and (? = '' or g.chain = ?)
                         order by g.chain, g.id
                        """, (rs, rowNum) -> new GasAccountRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("custody_address_id", UUID.class),
                        rs.getString("chain"),
                        rs.getString("network"),
                        rs.getString("native_symbol"),
                        rs.getString("address"),
                        rs.getString("memo"),
                        rs.getLong("derivation_child"),
                        rs.getString("account_id"),
                        rs.getBigDecimal("available_balance"),
                        rs.getBigDecimal("locked_balance"),
                        rs.getBigDecimal("total_balance"),
                        rs.getBigDecimal("low_balance_threshold"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenantId, blankToEmpty(chain), blankToEmpty(chain));
    }
    public Optional<IdempotencyRecord> findIdempotency(UUID tenantId, String key, String operation) {
        return jdbc.query("""
                        select request_hash, response_status, response_body::text as response_body, expires_at
                         from custody_idempotency_key
                         where tenant_id = ? and idempotency_key = ? and operation = ?
                           and (expires_at is null or expires_at > now())
                        """, (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        instantOrNull(rs.getTimestamp("expires_at"))),
                tenantId, key, operation).stream().findFirst();
    }

    public boolean beginIdempotency(UUID tenantId, String key, String operation,
                                    String requestHash, Instant expiresAt) {
        return jdbc.query("""
                        insert into custody_idempotency_key(
                            tenant_id, idempotency_key, operation, request_hash, expires_at)
                        values (?, ?, ?, ?, ?)
                        on conflict (tenant_id, idempotency_key, operation) do update
                           set request_hash = excluded.request_hash,
                               response_status = null,
                               response_body = null,
                               expires_at = excluded.expires_at,
                               created_at = now()
                         where custody_idempotency_key.expires_at <= now()
                        returning true
                        """, (rs, rowNum) -> rs.getBoolean(1),
                tenantId, key, operation, requestHash, timestampOrNull(expiresAt))
                .stream().findFirst().orElse(false);
    }

    public void completeIdempotency(UUID tenantId, String key, String operation,
                                    int responseStatus, String responseJson) {
        if (jdbc.update("""
                        update custody_idempotency_key
                           set response_status = ?, response_body = cast(? as jsonb)
                         where tenant_id = ? and idempotency_key = ? and operation = ?
                           and response_status is null
                        """, responseStatus, responseJson, tenantId, key, operation) != 1) {
            throw new IllegalStateException("idempotency reservation is missing or already completed");
        }
    }

    public WebhookEndpointRecord insertWebhookEndpoint(
            UUID id, UUID tenantId, String name, String url, String encryptedSecret,
            String verificationTokenHash, UUID createdBy) {
        jdbc.update("""
                        insert into custody_webhook_endpoint(
                            id, tenant_id, name, url, secret_ciphertext,
                            verification_token_hash, created_by)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, id, tenantId, name, url, encryptedSecret,
                verificationTokenHash, createdBy);
        return requireWebhookEndpoint(tenantId, id);
    }
    public WebhookEndpointRecord requireWebhookEndpoint(UUID tenantId, UUID endpointId) {
        return jdbc.query("""
                        select id, tenant_id, name, url, secret_ciphertext,
                               status, verification_token_hash, verified_at, last_delivery_at,
                               created_at, updated_at
                          from custody_webhook_endpoint
                         where tenant_id = ? and id = ?
                        """, (rs, rowNum) -> mapWebhookEndpoint(rs), tenantId, endpointId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("webhook endpoint not found"));
    }
    public List<Map<String, Object>> listWebhookEndpoints(UUID tenantId) {
        return jdbc.query("""
                        select e.id, e.name, e.url, e.status,
                               e.verified_at, e.last_delivery_at, e.created_at, e.updated_at,
                               count(d.id) filter (
                                   where d.created_at >= now() - interval '24 hours') as delivery_count_24h,
                               count(d.id) filter (
                                   where d.status = 'DELIVERED'
                                     and d.created_at >= now() - interval '24 hours') as delivered_count_24h
                          from custody_webhook_endpoint e
                          left join custody_webhook_delivery d on d.endpoint_id = e.id
                         where e.tenant_id = ?
                         group by e.id
                         order by e.created_at desc
                        """, (rs, rowNum) -> {
                    long attempts = rs.getLong("delivery_count_24h");
                    long delivered = rs.getLong("delivered_count_24h");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("name", rs.getString("name"));
                    row.put("url", rs.getString("url"));
                    row.put("status", rs.getString("status"));
                    row.put("verifiedAt", instantOrNull(rs.getTimestamp("verified_at")));
                    row.put("lastDeliveryAt", instantOrNull(rs.getTimestamp("last_delivery_at")));
                    row.put("deliveryCount24h", attempts);
                    row.put("successRate24h", attempts == 0 ? null : delivered * 100.0d / attempts);
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId);
    }
    public void markWebhookVerified(UUID tenantId, UUID endpointId) {
        if (jdbc.update("""
                        update custody_webhook_endpoint
                           set status = 'ACTIVE', verified_at = now(),
                               verification_token_hash = null, updated_at = now()
                         where tenant_id = ? and id = ?
                           and status = 'PENDING_VERIFICATION'
                        """, tenantId, endpointId) != 1) {
            throw new IllegalStateException("webhook endpoint is not pending verification");
        }
    }
    public void setWebhookStatus(UUID tenantId, UUID endpointId, String status) {
        if (jdbc.update("""
                        update custody_webhook_endpoint
                           set status = ?, updated_at = now()
                         where tenant_id = ? and id = ?
                        """, status, tenantId, endpointId) != 1) {
            throw new IllegalArgumentException("webhook endpoint not found");
        }
    }

    public List<Map<String, Object>> listWebhookDeliveries(UUID tenantId, UUID endpointId,
                                                            String status, int limit, int offset) {
        String endpointPredicate = endpointId == null ? "" : "and d.endpoint_id = ?";
        String statusPredicate = status == null ? "" : "and d.status = ?";
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if (endpointId != null) {
            parameters.add(endpointId);
        }
        if (status != null) {
            parameters.add(status);
        }
        parameters.add(Math.min(Math.max(limit, 1), 200));
        parameters.add(Math.max(offset, 0));
        return jdbc.query("""
                        select d.id, d.endpoint_id, d.event_id, e.event_type,
                               e.aggregate_type, e.aggregate_id, d.status, d.attempt_count,
                               d.total_attempt_count, d.manual_retry_count,
                               d.next_attempt_at, d.next_attempt_trigger,
                               d.last_http_status, d.last_error,
                               d.delivered_at, d.created_at, d.updated_at
                          from custody_webhook_delivery d
                          join custody_event e on e.id = d.event_id
                         where d.tenant_id = ?
                           %s
                           %s
                         order by d.created_at desc, d.id
                         limit ? offset ?
                        """.formatted(endpointPredicate, statusPredicate), (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("endpointId", rs.getObject("endpoint_id", UUID.class));
                    row.put("eventId", rs.getObject("event_id", UUID.class));
                    row.put("eventType", rs.getString("event_type"));
                    row.put("aggregateType", rs.getString("aggregate_type"));
                    row.put("aggregateId", rs.getString("aggregate_id"));
                    row.put("status", rs.getString("status"));
                    row.put("attemptCount", rs.getInt("attempt_count"));
                    row.put("totalAttemptCount", rs.getInt("total_attempt_count"));
                    row.put("manualRetryCount", rs.getInt("manual_retry_count"));
                    row.put("nextAttemptAt", instantOrNull(rs.getTimestamp("next_attempt_at")));
                    row.put("nextAttemptTrigger", rs.getString("next_attempt_trigger"));
                    row.put("lastHttpStatus", rs.getObject("last_http_status"));
                    row.put("lastError", rs.getString("last_error"));
                    row.put("deliveredAt", instantOrNull(rs.getTimestamp("delivered_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, parameters.toArray());
    }

    @Transactional(rollbackFor = Throwable.class)
    public List<WebhookDeliveryTask> claimWebhookDeliveries(String workerId, int limit) {
        List<WebhookDeliveryTask> tasks = jdbc.query("""
                        with candidates as (
                            select d.id,
                                   case when d.status = 'DELIVERING'
                                       then 'RECOVERY'
                                       else d.next_attempt_trigger
                                   end as attempt_trigger
                              from custody_webhook_delivery d
                              join custody_webhook_endpoint ep
                                on ep.id = d.endpoint_id and ep.status = 'ACTIVE'
                             where (
                                   d.status in ('PENDING', 'RETRY')
                                   and d.next_attempt_at <= now()
                               ) or (
                                   d.status = 'DELIVERING'
                                   and d.locked_at < now() - interval '5 minutes'
                               )
                             order by d.next_attempt_at, d.created_at
                             for update skip locked
                             limit ?
                        ),
                        claimed as (
                            update custody_webhook_delivery d
                               set status = 'DELIVERING', locked_by = ?, locked_at = now(),
                                   attempt_count = attempt_count + 1,
                                   total_attempt_count = total_attempt_count + 1,
                                   next_attempt_trigger = 'AUTOMATIC',
                                   updated_at = now()
                              from candidates c
                             where d.id = c.id
                            returning d.id, d.tenant_id, d.endpoint_id, d.event_id,
                                      d.attempt_count, d.total_attempt_count,
                                      d.manual_retry_count, c.attempt_trigger
                        )
                        select c.id, c.tenant_id, c.endpoint_id, c.event_id,
                               c.attempt_count, c.total_attempt_count, c.manual_retry_count,
                               c.attempt_trigger, gen_random_uuid() as attempt_id,
                               ep.url, ep.secret_ciphertext, ev.event_type,
                               ev.payload::text as payload
                          from claimed c
                          join custody_webhook_endpoint ep on ep.id = c.endpoint_id
                          join custody_event ev on ev.id = c.event_id
                         where ep.status = 'ACTIVE'
                        """, (rs, rowNum) -> new WebhookDeliveryTask(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getInt("attempt_count"),
                        rs.getInt("total_attempt_count"),
                        rs.getInt("manual_retry_count"),
                        rs.getString("attempt_trigger"),
                        rs.getObject("attempt_id", UUID.class),
                        workerId,
                        rs.getString("url"),
                        rs.getString("secret_ciphertext"),
                        rs.getString("event_type"),
                        rs.getString("payload")),
                Math.min(Math.max(limit, 1), 100), workerId);
        for (WebhookDeliveryTask task : tasks) {
            if ("RECOVERY".equals(task.attemptTrigger())) {
                jdbc.update("""
                                update custody_webhook_delivery_attempt
                                   set status = 'FAILED',
                                       error_message =
                                           'worker lease expired; delivery was safely reclaimed',
                                       completed_at = now(),
                                       duration_ms = greatest(
                                           0,
                                           (extract(epoch from (now() - started_at)) * 1000)::bigint)
                                 where delivery_id = ? and status = 'IN_PROGRESS'
                                """, task.id());
            }
            jdbc.update("""
                            insert into custody_webhook_delivery_attempt(
                                id, tenant_id, delivery_id, attempt_number, retry_cycle,
                                trigger, worker_id)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """, task.attemptId(), task.tenantId(), task.id(),
                    task.totalAttemptCount(), task.manualRetryCount(),
                    task.attemptTrigger(), workerId);
        }
        return tasks;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markWebhookDelivered(WebhookDeliveryTask task, int httpStatus, String response,
                                     long durationMs) {
        int accepted = jdbc.update("""
                        update custody_webhook_delivery
                           set status = 'DELIVERED', last_http_status = ?, last_response = ?,
                               last_error = null, delivered_at = now(), locked_by = null,
                               locked_at = null, updated_at = now()
                         where id = ? and status = 'DELIVERING' and locked_by = ?
                           and total_attempt_count = ?
                        """, httpStatus, truncate(response, 4096), task.id(),
                task.workerId(), task.totalAttemptCount());
        jdbc.update("""
                        update custody_webhook_delivery_attempt
                           set status = 'DELIVERED', http_status = ?, response_body = ?,
                               completed_at = now(), duration_ms = ?
                         where id = ? and status = 'IN_PROGRESS'
                        """, httpStatus, truncate(response, 4096), Math.max(durationMs, 0L),
                task.attemptId());
        if (accepted == 1) {
            jdbc.update("""
                    update custody_webhook_endpoint
                       set last_delivery_at = now(), updated_at = now()
                     where id = ?
                    """, task.endpointId());
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markWebhookFailed(WebhookDeliveryTask task, Integer httpStatus, String error,
                                  String response, Instant nextAttempt, boolean terminal,
                                  long durationMs) {
        jdbc.update("""
                        update custody_webhook_delivery
                           set status = ?, last_http_status = ?, last_error = ?,
                               last_response = ?, next_attempt_at = ?, locked_by = null,
                               locked_at = null, updated_at = now()
                         where id = ? and status = 'DELIVERING' and locked_by = ?
                           and total_attempt_count = ?
                        """, terminal ? "FAILED" : "RETRY", httpStatus, truncate(error, 4096),
                truncate(response, 4096), timestampOrNull(nextAttempt), task.id(),
                task.workerId(), task.totalAttemptCount());
        jdbc.update("""
                        update custody_webhook_delivery_attempt
                           set status = ?, http_status = ?, error_message = ?,
                               response_body = ?, next_attempt_at = ?,
                               completed_at = now(), duration_ms = ?
                         where id = ? and status = 'IN_PROGRESS'
                        """, terminal ? "FAILED" : "RETRY_SCHEDULED", httpStatus,
                truncate(error, 4096), truncate(response, 4096),
                terminal ? null : Timestamp.from(nextAttempt), Math.max(durationMs, 0L),
                task.attemptId());
    }
    public void retryWebhookDelivery(UUID tenantId, UUID deliveryId) {
        if (jdbc.update("""
                        update custody_webhook_delivery
                           set status = 'RETRY', attempt_count = 0,
                               manual_retry_count = manual_retry_count + 1,
                               next_attempt_trigger = 'MANUAL',
                               next_attempt_at = now(), locked_by = null,
                               locked_at = null, updated_at = now()
                         where tenant_id = ? and id = ? and status in ('FAILED', 'RETRY')
                        """, tenantId, deliveryId) != 1) {
            throw new IllegalStateException("failed or retryable webhook delivery not found");
        }
    }
    public int retryFailedWebhookDeliveries(UUID tenantId, UUID endpointId) {
        return jdbc.update("""
                        update custody_webhook_delivery
                           set status = 'RETRY', attempt_count = 0,
                               manual_retry_count = manual_retry_count + 1,
                               next_attempt_trigger = 'MANUAL',
                               next_attempt_at = now(), locked_by = null,
                               locked_at = null, updated_at = now()
                         where tenant_id = ? and endpoint_id = ?
                           and status in ('FAILED', 'RETRY')
                        """, tenantId, endpointId);
    }

    public List<Map<String, Object>> listWebhookDeliveryAttempts(
            UUID tenantId, UUID deliveryId, int limit, int offset) {
        return jdbc.query("""
                        select a.id, a.attempt_number, a.retry_cycle, a.trigger, a.status,
                               a.http_status, a.error_message, a.response_body,
                               a.next_attempt_at, a.started_at, a.completed_at, a.duration_ms
                          from custody_webhook_delivery_attempt a
                          join custody_webhook_delivery d on d.id = a.delivery_id
                         where a.tenant_id = ? and a.delivery_id = ?
                           and d.tenant_id = ?
                         order by a.attempt_number desc
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("attemptNumber", rs.getInt("attempt_number"));
                    row.put("retryCycle", rs.getInt("retry_cycle"));
                    row.put("trigger", rs.getString("trigger"));
                    row.put("status", rs.getString("status"));
                    row.put("httpStatus", rs.getObject("http_status"));
                    row.put("errorMessage", rs.getString("error_message"));
                    row.put("responseBody", rs.getString("response_body"));
                    row.put("nextAttemptAt", instantOrNull(rs.getTimestamp("next_attempt_at")));
                    row.put("startedAt", rs.getTimestamp("started_at").toInstant());
                    row.put("completedAt", instantOrNull(rs.getTimestamp("completed_at")));
                    row.put("durationMs", rs.getObject("duration_ms"));
                    return row;
                }, tenantId, deliveryId, tenantId,
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }

    public void insertCustodyWithdrawal(
            UUID id, UUID tenantId, UUID custodyAddressId, String orderNo,
            String externalReference, String idempotencyKey, String chain,
            String assetSymbol, String toAddress, java.math.BigDecimal amount,
            java.math.BigDecimal fee, String status, String createdByType, String createdById) {
        jdbc.update("""
                        insert into custody_withdrawal(
                            id, tenant_id, custody_address_id, withdrawal_order_id,
                            order_no, external_reference,
                            idempotency_key, chain, asset_symbol, to_address, amount, fee,
                            status, created_by_type, created_by_id)
                        select ?, ?, ?, orders.id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                          from withdrawal_order orders
                         where orders.tenant_id = ? and orders.chain = ? and orders.order_no = ?
                        """, id, tenantId, custodyAddressId, orderNo, externalReference,
                idempotencyKey, chain, assetSymbol, toAddress, amount, fee, status,
                createdByType, createdById, tenantId, chain, orderNo);
    }

    public List<Map<String, Object>> listCustodyWithdrawals(
            UUID tenantId, String chain, String assetSymbol, String status,
            String search, int limit, int offset) {
        String normalizedSearch = search == null ? "" : search.trim();
        return jdbc.query("""
                        select w.id, w.custody_address_id, w.order_no, w.external_reference,
                               w.chain, w.asset_symbol, w.to_address, w.amount, w.fee,
                               wo.tx_hash, wo.status, wo.error_message, w.created_by_type,
                               a.address as source_address, a.subject,
                               w.created_at, greatest(w.updated_at, wo.updated_at) as updated_at
                          from custody_withdrawal w
                          join withdrawal_order wo
                            on wo.tenant_id = w.tenant_id
                           and wo.chain = w.chain and wo.order_no = w.order_no
                          join custody_address a
                            on a.tenant_id = w.tenant_id and a.id = w.custody_address_id
                         where w.tenant_id = ?
                           and (? = '' or w.chain = ?)
                           and (? = '' or w.asset_symbol = ?)
                           and (? = '' or wo.status = ?)
                           and (? = ''
                                or w.order_no ilike '%' || ? || '%'
                                or coalesce(w.external_reference, '') ilike '%' || ? || '%'
                                or w.to_address ilike '%' || ? || '%'
                                or coalesce(wo.tx_hash, '') ilike '%' || ? || '%'
                                or a.address ilike '%' || ? || '%'
                                or a.subject ilike '%' || ? || '%')
                         order by w.created_at desc, w.id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("custodyAddressId", rs.getObject("custody_address_id", UUID.class));
                    row.put("orderNo", rs.getString("order_no"));
                    row.put("externalReference", rs.getString("external_reference"));
                    row.put("chain", rs.getString("chain"));
                    row.put("assetSymbol", rs.getString("asset_symbol"));
                    row.put("toAddress", rs.getString("to_address"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("fee", rs.getBigDecimal("fee"));
                    row.put("txHash", rs.getString("tx_hash"));
                    row.put("status", rs.getString("status"));
                    row.put("errorMessage", rs.getString("error_message"));
                    row.put("createdByType", rs.getString("created_by_type"));
                    row.put("sourceAddress", rs.getString("source_address"));
                    row.put("subject", rs.getString("subject"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId,
                blankToEmpty(chain), blankToEmpty(chain),
                blankToEmpty(assetSymbol), blankToEmpty(assetSymbol),
                blankToEmpty(status), blankToEmpty(status),
                normalizedSearch, normalizedSearch, normalizedSearch, normalizedSearch,
                normalizedSearch, normalizedSearch, normalizedSearch,
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }

    public List<Map<String, Object>> listCustodyDeposits(
            UUID tenantId, String chain, String assetSymbol, String status,
            String search, int limit, int offset) {
        String normalizedSearch = search == null ? "" : search.trim();
        return jdbc.query("""
                        select d.id, d.custody_address_id, a.address, a.subject,
                               d.chain, d.asset_symbol, d.tx_hash, d.log_index, d.amount,
                               d.status, d.credited_at, d.created_at, d.updated_at
                          from custody_deposit d
                          join custody_address a
                            on a.tenant_id = d.tenant_id and a.id = d.custody_address_id
                         where d.tenant_id = ?
                           and not exists (
                               select 1 from custody_gas_account g
                                where g.custody_address_id = d.custody_address_id
                           )
                           and (? = '' or d.chain = ?)
                           and (? = '' or d.asset_symbol = ?)
                           and (? = '' or d.status = ?)
                           and (? = ''
                                or d.tx_hash ilike '%' || ? || '%'
                                or a.address ilike '%' || ? || '%'
                                or a.subject ilike '%' || ? || '%'
                                or coalesce(a.label, '') ilike '%' || ? || '%')
                         order by d.created_at desc, d.id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("custodyAddressId", rs.getObject("custody_address_id", UUID.class));
                    row.put("address", rs.getString("address"));
                    row.put("subject", rs.getString("subject"));
                    row.put("chain", rs.getString("chain"));
                    row.put("assetSymbol", rs.getString("asset_symbol"));
                    row.put("txHash", rs.getString("tx_hash"));
                    row.put("logIndex", rs.getLong("log_index"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("status", rs.getString("status"));
                    row.put("creditedAt", instantOrNull(rs.getTimestamp("credited_at")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return row;
                }, tenantId,
                blankToEmpty(chain), blankToEmpty(chain),
                blankToEmpty(assetSymbol), blankToEmpty(assetSymbol),
                blankToEmpty(status), blankToEmpty(status),
                normalizedSearch, normalizedSearch, normalizedSearch,
                normalizedSearch, normalizedSearch,
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public List<WithdrawalStatusChange> findWithdrawalStatusChanges(int limit) {
        return jdbc.query("""
                        select w.id, w.tenant_id, w.custody_address_id, w.order_no,
                               w.external_reference, w.chain, w.asset_symbol, w.to_address,
                               w.amount, w.fee, w.status as previous_status,
                               wo.status as next_status, wo.tx_hash, wo.error_message,
                               wo.debit_account_id, a.source as address_source
                          from custody_withdrawal w
                          join withdrawal_order wo
                            on wo.tenant_id = w.tenant_id
                           and wo.chain = w.chain and wo.order_no = w.order_no
                          join custody_address a
                            on a.tenant_id = w.tenant_id and a.id = w.custody_address_id
                         where w.status <> wo.status
                         order by wo.updated_at, w.id
                         limit ?
                        """, (rs, rowNum) -> new WithdrawalStatusChange(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("custody_address_id", UUID.class),
                        rs.getString("order_no"),
                        rs.getString("external_reference"),
                        rs.getString("chain"),
                        rs.getString("asset_symbol"),
                        rs.getString("to_address"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("fee"),
                        rs.getString("previous_status"),
                        rs.getString("next_status"),
                        rs.getString("tx_hash"),
                        rs.getString("error_message"),
                        rs.getString("debit_account_id"),
                        rs.getString("address_source")),
                Math.min(Math.max(limit, 1), 200));
    }

    @Transactional(rollbackFor = Throwable.class)
    public boolean applyWithdrawalStatusChange(WithdrawalStatusChange change,
                                               UUID eventId, String eventType, String payloadJson) {
        int updated = jdbc.update("""
                        update custody_withdrawal
                           set status = ?, updated_at = now()
                         where id = ? and status = ?
                        """, change.nextStatus(), change.id(), change.previousStatus());
        if (updated != 1) {
            return false;
        }
        if (eventType != null) {
            insertEventWithDeliveries(
                    eventId, change.tenantId(), eventType, "WITHDRAWAL", change.orderNo(),
                    payloadJson, "API".equals(change.addressSource()));
        }
        if ("CONFIRMED".equals(change.nextStatus())) {
            jdbc.update("""
                            insert into custody_ledger_entry(
                                id, tenant_id, custody_address_id, chain, asset_symbol,
                                account_id, entry_type, direction, amount,
                                reference_type, reference_id)
                            values (?, ?, ?, ?, ?, ?, 'WITHDRAWAL', 'DEBIT', ?,
                                    'WITHDRAWAL', ?)
                            on conflict (tenant_id, entry_type, reference_type, reference_id)
                            do nothing
                            """, UUID.randomUUID(), change.tenantId(), change.custodyAddressId(),
                    change.chain(), change.assetSymbol(), change.debitAccountId(),
                    change.amount().add(change.fee()), change.orderNo());
            findGasUsage(change.id()).ifPresent(usage -> {
                GasPricingMetadata metadata = gasPricingMetadata(
                        change.chain(), change.assetSymbol());
                NetworkFee networkFee = confirmedNetworkFee(
                        change.chain(), change.orderNo(), change.txHash(), metadata.decimals())
                        .orElse(new NetworkFee(
                                usage.reservedAmount(), "CONFIGURED_RESERVE"));
                settleGasUsage(change.id(), networkFee.amount(),
                        networkFee.pricingSource(), change.txHash());
            });
        } else if (Set.of("FAILED", "REJECTED", "CANCELLED")
                .contains(change.nextStatus())) {
            findGasUsage(change.id()).ifPresent(usage ->
                    releaseGasUsage(change.id(),
                            "withdrawal ended as " + change.nextStatus()));
        }
        return true;
    }

    @Transactional(rollbackFor = Throwable.class)
    public UUID insertEventWithDeliveries(UUID eventId, UUID tenantId, String eventType,
                                          String aggregateType, String aggregateId,
                                          String payloadJson, boolean webhookEligible) {
        UUID persisted = jdbc.query("""
                        insert into custody_event(
                            id, tenant_id, event_type, aggregate_type, aggregate_id, payload)
                        values (?, ?, ?, ?, ?, cast(? as jsonb))
                        on conflict (tenant_id, event_type, aggregate_type, aggregate_id)
                        do nothing
                        returning id
                        """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                eventId, tenantId, eventType, aggregateType, aggregateId, payloadJson)
                .stream().findFirst().orElse(null);
        if (persisted == null) {
            persisted = jdbc.queryForObject("""
                            select id
                              from custody_event
                             where tenant_id = ? and event_type = ?
                               and aggregate_type = ? and aggregate_id = ?
                            """, UUID.class, tenantId, eventType, aggregateType, aggregateId);
        }
        if (persisted == null) {
            throw new IllegalStateException("failed to persist custody event");
        }
        jdbc.update("""
                        insert into custody_webhook_delivery(id, tenant_id, endpoint_id, event_id)
                        select gen_random_uuid(), e.tenant_id, e.id, ?
                         from custody_webhook_endpoint e
                         where e.tenant_id = ? and e.status = 'ACTIVE'
                           and ?
                        on conflict (endpoint_id, event_id) do nothing
                        """, persisted, tenantId, webhookEligible);
        jdbc.update("""
                        update custody_event
                           set status = 'PUBLISHED',
                               published_at = coalesce(published_at, now())
                         where id = ?
                        """, persisted);
        return persisted;
    }

    public void audit(UUID tenantId, String actorType, String actorId, String action,
                      String resourceType, String resourceId, String sourceIp, String detailsJson) {
        jdbc.update("""
                        insert into custody_audit_log(
                            id, tenant_id, actor_type, actor_id, action, resource_type,
                            resource_id, source_ip, details)
                        values (?, ?, ?, ?, ?, ?, ?, cast(nullif(?, '') as inet), cast(? as jsonb))
                        """, UUID.randomUUID(), tenantId, actorType, actorId, action, resourceType,
                resourceId, sourceIp, detailsJson == null ? "{}" : detailsJson);
    }
    public List<Map<String, Object>> listAudit(UUID tenantId, int limit, int offset) {
        return jdbc.query("""
                        select id, actor_type, actor_id, action, resource_type, resource_id,
                               source_ip, details::text as details, created_at
                          from custody_audit_log
                         where tenant_id = ?
                         order by created_at desc, id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("actorType", rs.getString("actor_type"));
                    row.put("actorId", rs.getString("actor_id"));
                    row.put("action", rs.getString("action"));
                    row.put("resourceType", rs.getString("resource_type"));
                    row.put("resourceId", rs.getString("resource_id"));
                    row.put("sourceIp", rs.getString("source_ip"));
                    row.put("details", rs.getString("details"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    return row;
                }, tenantId, Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public List<Map<String, Object>> listPlatformAudit(int limit, int offset) {
        return jdbc.query("""
                        select id, actor_type, actor_id, action, resource_type, resource_id,
                               source_ip, details::text as details, created_at
                          from custody_audit_log
                         where tenant_id is null
                         order by created_at desc, id
                         limit ? offset ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("actorType", rs.getString("actor_type"));
                    row.put("actorId", rs.getString("actor_id"));
                    row.put("action", rs.getString("action"));
                    row.put("resourceType", rs.getString("resource_type"));
                    row.put("resourceId", rs.getString("resource_id"));
                    row.put("sourceIp", rs.getString("source_ip"));
                    row.put("details", rs.getString("details"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    return row;
                }, Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }
    public int cleanupExpiredSecurityRows() {
        int nonces = jdbc.update("delete from custody_api_nonce where expires_at < now()");
        int sessions = jdbc.update("""
                delete from custody_session
                 where expires_at < now() - interval '7 days'
                    or revoked_at < now() - interval '7 days'
                """);
        int idempotency = jdbc.update("delete from custody_idempotency_key where expires_at < now()");
        return nonces + sessions + idempotency;
    }
    private AuthUser mapAuthUser(java.sql.ResultSet rs) throws SQLException {
        return new AuthUser(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_slug"),
                rs.getString("tenant_status"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getInt("failed_login_count"),
                instantOrNull(rs.getTimestamp("locked_until")));
    }
    private ApiKeyRecord mapApiKey(java.sql.ResultSet rs) throws SQLException {
        return new ApiKeyRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_slug"),
                rs.getString("tenant_status"),
                rs.getBoolean("ip_allowlist_enabled"),
                rs.getString("key_id"),
                rs.getString("name"),
                rs.getString("secret_ciphertext"),
                rs.getString("status"),
                instantOrNull(rs.getTimestamp("expires_at")),
                rs.getTimestamp("created_at").toInstant());
    }
    private AddressRecord mapAddress(java.sql.ResultSet rs) throws SQLException {
        return new AddressRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getLong("chain_address_id"),
                rs.getString("chain"),
                rs.getString("network"),
                rs.getString("address"),
                rs.getString("memo"),
                rs.getString("subject"),
                rs.getString("label"),
                rs.getString("metadata"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getInt("derivation_subject"),
                rs.getLong("address_version"),
                rs.getLong("derivation_child"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
    private WebhookEndpointRecord mapWebhookEndpoint(java.sql.ResultSet rs) throws SQLException {
        return new WebhookEndpointRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("name"),
                rs.getString("url"),
                rs.getString("secret_ciphertext"),
                rs.getString("status"),
                rs.getString("verification_token_hash"),
                instantOrNull(rs.getTimestamp("verified_at")),
                instantOrNull(rs.getTimestamp("last_delivery_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static Timestamp timestampOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record TenantRecord(
            UUID id,
            String slug,
            String name,
            String status,
            int derivationNamespace,
            boolean ipAllowlistEnabled,
            String displayCurrency,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AuthUser(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantStatus,
            String email,
            String displayName,
            String passwordHash,
            String role,
            String status,
            int failedLoginCount,
            Instant lockedUntil
    ) {
    }

    public record SessionRecord(
            UUID sessionId,
            UUID userId,
            UUID tenantId,
            String tenantSlug,
            String email,
            String displayName,
            String role,
            String userStatus,
            String tenantStatus,
            Instant expiresAt
    ) {
    }

    public record ApiKeyRecord(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantStatus,
            boolean ipAllowlistEnabled,
            String keyId,
            String name,
            String secretCiphertext,
            String status,
            Instant expiresAt,
            Instant createdAt
    ) {
    }

    public record AddressRecord(
            UUID id,
            UUID tenantId,
            long chainAddressId,
            String chain,
            String network,
            String address,
            String memo,
            String subject,
            String label,
            String metadataJson,
            String source,
            String status,
            int derivationSubject,
            long addressVersion,
            long derivationChild,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record GasAccountRecord(
            UUID id,
            UUID tenantId,
            UUID custodyAddressId,
            String chain,
            String network,
            String nativeSymbol,
            String address,
            String memo,
            long childIndex,
            String accountId,
            java.math.BigDecimal availableBalance,
            java.math.BigDecimal lockedBalance,
            java.math.BigDecimal totalBalance,
            java.math.BigDecimal lowBalanceThreshold,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public boolean lowBalance() {
            return "ACTIVE".equals(status)
                    && availableBalance.compareTo(lowBalanceThreshold) < 0;
        }
    }

    public record IdempotencyRecord(
            String requestHash,
            Integer responseStatus,
            String responseJson,
            Instant expiresAt
    ) {
    }

    public record WebhookEndpointRecord(
            UUID id,
            UUID tenantId,
            String name,
            String url,
            String secretCiphertext,
            String status,
            String verificationTokenHash,
            Instant verifiedAt,
            Instant lastDeliveryAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record WebhookDeliveryTask(
            UUID id,
            UUID tenantId,
            UUID endpointId,
            UUID eventId,
            int attemptCount,
            int totalAttemptCount,
            int manualRetryCount,
            String attemptTrigger,
            UUID attemptId,
            String workerId,
            String url,
            String secretCiphertext,
            String eventType,
            String payload
    ) {
    }

    public record WithdrawalStatusChange(
            UUID id,
            UUID tenantId,
            UUID custodyAddressId,
            String orderNo,
            String externalReference,
            String chain,
            String assetSymbol,
            String toAddress,
            java.math.BigDecimal amount,
            java.math.BigDecimal fee,
            String previousStatus,
            String nextStatus,
            String txHash,
            String errorMessage,
            String debitAccountId,
            String addressSource
    ) {
    }

    public record GasPricingMetadata(
            String family,
            String nativeSymbol,
            long defaultFeeRate,
            int decimals,
            boolean requestedNative
    ) {
    }

    public record GasUsageRecord(
            UUID id,
            UUID tenantId,
            UUID gasAccountId,
            String operationType,
            UUID operationId,
            String referenceNo,
            String chain,
            String nativeSymbol,
            java.math.BigDecimal reservedAmount,
            java.math.BigDecimal actualAmount,
            String status,
            String pricingSource,
            String txHash,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant settledAt
    ) {
    }

    public record NetworkFee(
            java.math.BigDecimal amount,
            String pricingSource
    ) {
    }
}
