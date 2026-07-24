package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class AptosTenantIntegrationFixture {
    private static final UUID TENANT_ID = namedUuid("aptos-localnet-tenant");
    private static final UUID ADMIN_ID = namedUuid("aptos-localnet-admin");

    private AptosTenantIntegrationFixture() {
    }

    static TenantAddress attachDepositAddress(JdbcTemplate jdbc, ChainAddressRecord address) {
        ensureTenant(jdbc);
        attachAddressRows(jdbc, address);
        Long chainAddressId = jdbc.queryForObject("""
                select id from chain_address
                 where tenant_id = ? and chain = 'APTOS' and asset_symbol = 'APT'
                   and lower(address) = lower(?) and wallet_role = 'DEPOSIT'
                 order by id limit 1
                """, Long.class, TENANT_ID, address.getAddress());
        if (chainAddressId == null) {
            throw new IllegalStateException("missing Aptos native owner address");
        }
        UUID custodyAddressId = namedUuid("aptos-custody-" + address.getAddress());
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, address_version, source, status,
                    derivation_subject, derivation_child, created_by)
                values (?, ?, ?, 'APTOS', 'localnet', ?, ?, 0, 'API', 'ACTIVE', ?, ?, ?)
                on conflict (chain_address_id) do nothing
                """, custodyAddressId, TENANT_ID, chainAddressId, address.getAddress(),
                "aptos-user-" + address.getUserId(), Math.toIntExact(address.getUserId()),
                address.getAddressIndex(), ADMIN_ID);
        UUID storedCustodyId = jdbc.queryForObject("""
                select id from custody_address
                 where tenant_id = ? and chain_address_id = ?
                """, UUID.class, TENANT_ID, chainAddressId);
        return new TenantAddress(TENANT_ID, storedCustodyId);
    }

    static void attachPlatformAddress(JdbcTemplate jdbc, ChainAddressRecord address) {
        ensureTenant(jdbc);
        attachAddressRows(jdbc, address);
    }

    private static void attachAddressRows(JdbcTemplate jdbc, ChainAddressRecord address) {
        int updated = jdbc.update("""
                update chain_address set tenant_id = ?, updated_at = now()
                 where chain = 'APTOS' and lower(address) = lower(?)
                """, TENANT_ID, address.getAddress());
        if (updated == 0) {
            throw new IllegalStateException("Aptos address was not persisted: " + address.getAddress());
        }
    }

    private static void ensureTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, 'aptos-localnet', 'Aptos localnet tenant', 6111)
                on conflict (id) do nothing
                """, TENANT_ID);
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, 'aptos-localnet@example.test', 'Aptos test admin',
                        'test-only-hash', 'TENANT_ADMIN', 'ACTIVE')
                on conflict (id) do nothing
                """, ADMIN_ID, TENANT_ID);
        jdbc.update("""
                insert into custody_tenant_chain(
                    tenant_id, chain, status, opened_by, opened_at)
                values (?, 'APTOS', 'ACTIVE', ?, now())
                on conflict (tenant_id, chain) do update set
                    status = 'ACTIVE', opened_by = excluded.opened_by,
                    opened_at = coalesce(custody_tenant_chain.opened_at, excluded.opened_at),
                    closed_by = null, closed_at = null, updated_at = now()
                """, TENANT_ID, ADMIN_ID);
    }

    private static UUID namedUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    record TenantAddress(UUID tenantId, UUID custodyAddressId) {
    }
}
