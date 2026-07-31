package com.surprising.wallet.chain.solana;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 测试辅助类 {@code SolanaTenantIntegrationFixture}，为相关测试提供隔离环境或共享数据。
 */
final class SolanaTenantIntegrationFixture {
    /**
     * 保存 {@code TENANT_ID}，用于标识测试中的交易、区块或业务记录。
     */
    private static final UUID TENANT_ID = UUID.nameUUIDFromBytes(
            "solana-localnet-tenant".getBytes(StandardCharsets.UTF_8));

    /**
     * 验证 {@code SolanaTenantIntegrationFixture} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private SolanaTenantIntegrationFixture() {
    }

    /**
     * 验证 {@code ensureTenant} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    static UUID ensureTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, 'solana-localnet', 'Solana localnet tenant', 2111)
                on conflict (id) do nothing
                """, TENANT_ID);
        return TENANT_ID;
    }

    /**
     * 验证 {@code attachDepositAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    static UUID attachDepositAddress(JdbcTemplate jdbc, ChainAddressRecord address) {
        ensureTenant(jdbc);
        Long chainAddressId = jdbc.queryForObject("""
                select id from chain_address
                 where tenant_id = ? and chain = 'SOLANA'
                   and asset_symbol = 'SOL' and account_id = ?
                   and wallet_role = 'DEPOSIT'
                 order by id limit 1
                """, Long.class, TENANT_ID, address.getAccountId());
        if (chainAddressId == null) {
            throw new IllegalStateException("missing Solana deposit address");
        }
        UUID custodyAddressId = UUID.nameUUIDFromBytes(
                ("solana-custody-" + address.getAddress()).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, address_version, source, status,
                    derivation_subject, derivation_child)
                values (?, ?, ?, 'SOLANA', 'localnet', ?, ?, 0, 'API', 'ACTIVE', ?, ?)
                on conflict (chain_address_id) do nothing
                """, custodyAddressId, TENANT_ID, chainAddressId, address.getAddress(),
                "solana-user-" + address.getUserId(), Math.toIntExact(address.getUserId()),
                address.getAddressIndex());
        return jdbc.queryForObject("""
                select id from custody_address
                 where tenant_id = ? and chain_address_id = ?
                """, UUID.class, TENANT_ID, chainAddressId);
    }
}
