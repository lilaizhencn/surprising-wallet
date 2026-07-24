package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class XrpTenantIntegrationFixture {
    private static final UUID TENANT_ID = UUID.nameUUIDFromBytes(
            "xrp-testnet-tenant".getBytes(StandardCharsets.UTF_8));

    private XrpTenantIntegrationFixture() {
    }

    static UUID ensureTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, 'xrp-testnet', 'XRP testnet tenant', 2117)
                on conflict (id) do nothing
                """, TENANT_ID);
        return TENANT_ID;
    }

    static void assignAddress(JdbcTemplate jdbc, ChainAddressRecord address) {
        ensureTenant(jdbc);
        int updated = jdbc.update("""
                update chain_address set tenant_id = ?, updated_at = now()
                 where chain = 'XRP' and asset_symbol = ?
                   and user_id = ? and biz = ? and address_index = ? and wallet_role = ?
                """, TENANT_ID, address.getAssetSymbol(), address.getUserId(), address.getBiz(),
                address.getAddressIndex(), address.getWalletRole());
        if (updated != 1) {
            throw new IllegalStateException("unable to assign XRP address to test tenant");
        }
    }

    static UUID attachDepositAddress(JdbcTemplate jdbc, ChainAddressRecord address) {
        assignAddress(jdbc, address);
        Long chainAddressId = jdbc.queryForObject("""
                select id from chain_address
                 where tenant_id = ? and chain = 'XRP'
                   and asset_symbol = 'XRP' and account_id = ?
                   and wallet_role = 'DEPOSIT'
                 order by id limit 1
                """, Long.class, TENANT_ID, address.getAccountId());
        if (chainAddressId == null) {
            throw new IllegalStateException("missing XRP deposit address");
        }
        UUID custodyAddressId = UUID.nameUUIDFromBytes(
                ("xrp-custody-" + address.getAddress()).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, address_version, source, status,
                    derivation_subject, derivation_child)
                values (?, ?, ?, 'XRP', 'testnet', ?, ?, 0, 'API', 'ACTIVE', ?, ?)
                on conflict (chain_address_id) do nothing
                """, custodyAddressId, TENANT_ID, chainAddressId, address.getAddress(),
                "xrp-user-" + address.getUserId(), Math.toIntExact(address.getUserId()),
                address.getAddressIndex());
        return jdbc.queryForObject("""
                select id from custody_address
                 where tenant_id = ? and chain_address_id = ?
                """, UUID.class, TENANT_ID, chainAddressId);
    }
}
