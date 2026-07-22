package com.surprising.wallet.service.chain.polkadot;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class PolkadotTenantIntegrationFixture {
    private static final UUID TENANT_ID = UUID.nameUUIDFromBytes(
            "polkadot-devnet-tenant".getBytes(StandardCharsets.UTF_8));

    private PolkadotTenantIntegrationFixture() {
    }

    static UUID ensureTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, 'polkadot-devnet', 'Polkadot devnet tenant', 2115)
                on conflict (id) do nothing
                """, TENANT_ID);
        return TENANT_ID;
    }

    static UUID attachDepositAddress(JdbcTemplate jdbc, ChainAddressRecord address) {
        ensureTenant(jdbc);
        Long chainAddressId = jdbc.queryForObject("""
                select id from chain_address
                 where tenant_id = ? and chain = 'DOT'
                   and asset_symbol = 'DOT' and account_id = ?
                   and wallet_role = 'DEPOSIT'
                 order by id limit 1
                """, Long.class, TENANT_ID, address.getAccountId());
        if (chainAddressId == null) {
            throw new IllegalStateException("missing DOT deposit address");
        }
        UUID custodyAddressId = UUID.nameUUIDFromBytes(
                ("dot-custody-" + address.getAddress()).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, address_version, source, status,
                    derivation_subject, derivation_child)
                values (?, ?, ?, 'DOT', 'devnet', ?, ?, 0, 'API', 'ACTIVE', ?, ?)
                on conflict (chain_address_id) do nothing
                """, custodyAddressId, TENANT_ID, chainAddressId, address.getAddress(),
                "dot-user-" + address.getUserId(), Math.toIntExact(address.getUserId()),
                address.getAddressIndex());
        return jdbc.queryForObject("""
                select id from custody_address
                 where tenant_id = ? and chain_address_id = ?
                """, UUID.class, TENANT_ID, chainAddressId);
    }
}
