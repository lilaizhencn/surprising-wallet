package com.surprising.wallet.custody.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CustodyTenantChainRepository {
    private final JdbcTemplate jdbc;

    public CustodyTenantChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChainRecord> list(UUID tenantId) {
        Map<String, List<TokenRecord>> tokensByChain = tokens().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        TokenRecord::chain, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return jdbc.query("""
                        select p.chain, p.network, p.family, p.native_symbol,
                               p.scan_enabled, p.withdraw_enabled, p.transfer_enabled,
                               coalesce(tc.status, 'CLOSED') as tenant_status,
                               tc.opened_at, tc.closed_at
                          from chain_profile p
                          left join custody_tenant_chain tc
                            on tc.tenant_id = ? and tc.chain = p.chain
                         where p.enabled = true
                         order by p.chain
                        """, (rs, rowNum) -> new ChainRecord(
                rs.getString("chain"),
                rs.getString("network"),
                rs.getString("family"),
                rs.getString("native_symbol"),
                rs.getBoolean("scan_enabled"),
                rs.getBoolean("withdraw_enabled"),
                rs.getBoolean("transfer_enabled"),
                rs.getString("tenant_status"),
                instantOrNull(rs.getTimestamp("opened_at")),
                instantOrNull(rs.getTimestamp("closed_at")),
                tokensByChain.getOrDefault(rs.getString("chain"), List.of())), tenantId);
    }

    public List<TokenRecord> tokens() {
        return jdbc.query("""
                select p.chain, a.symbol,
                       coalesce(t.token_standard, t.standard) as standard,
                       t.contract_address, t.decimals,
                       t.enabled as platform_enabled
                  from chain_profile p
                  join chain_asset a
                    on a.chain = p.chain and a.active = true and a.native_asset = false
                  join token_config t
                    on t.chain = p.chain and t.symbol = a.symbol
                   and lower(t.network) = lower(p.network)
                 where p.enabled = true
                 order by p.chain, a.symbol
                """, (rs, rowNum) -> new TokenRecord(
                rs.getString("chain"), rs.getString("symbol"), rs.getString("standard"),
                rs.getString("contract_address"), rs.getInt("decimals"),
                rs.getBoolean("platform_enabled")));
    }

    public boolean platformChainEnabled(String chain) {
        Boolean enabled = jdbc.queryForObject("""
                select exists(select 1 from chain_profile where chain = ? and enabled = true)
                """, Boolean.class, chain);
        return Boolean.TRUE.equals(enabled);
    }

    public boolean active(UUID tenantId, String chain) {
        Boolean active = jdbc.queryForObject("""
                select exists(
                    select 1
                      from custody_tenant_chain tc
                      join chain_profile p on p.chain = tc.chain and p.enabled = true
                     where tc.tenant_id = ? and tc.chain = ? and tc.status = 'ACTIVE'
                )
                """, Boolean.class, tenantId, chain);
        return Boolean.TRUE.equals(active);
    }

    public void setStatus(UUID tenantId, String chain, String status, UUID actorId) {
        jdbc.update("""
                insert into custody_tenant_chain(
                    tenant_id, chain, status, opened_by, opened_at,
                    closed_by, closed_at, updated_at)
                values (?, ?, ?,
                        case when ? = 'ACTIVE' then ?::uuid end,
                        case when ? = 'ACTIVE' then now() end,
                        case when ? = 'CLOSED' then ?::uuid end,
                        case when ? = 'CLOSED' then now() end,
                        now())
                on conflict (tenant_id, chain) do update set
                    status = excluded.status,
                    opened_by = case when excluded.status = 'ACTIVE' then excluded.opened_by
                                     else custody_tenant_chain.opened_by end,
                    opened_at = case when excluded.status = 'ACTIVE' then now()
                                     else custody_tenant_chain.opened_at end,
                    closed_by = case when excluded.status = 'CLOSED' then excluded.closed_by
                                     else null end,
                    closed_at = case when excluded.status = 'CLOSED' then now() else null end,
                    updated_at = now()
                """, tenantId, chain, status,
                status, actorId, status, status, actorId, status);
    }

    public boolean depositEnabled(UUID tenantId, String chain, String symbol) {
        return assetOperationEnabled(tenantId, chain, symbol, true);
    }

    public boolean withdrawalEnabled(UUID tenantId, String chain, String symbol) {
        return assetOperationEnabled(tenantId, chain, symbol, false);
    }

    private boolean assetOperationEnabled(UUID tenantId, String chain, String symbol,
                                          boolean deposit) {
        Boolean enabled = jdbc.queryForObject("""
                select exists(
                    select 1
                      from chain_asset a
                      join chain_profile p
                        on p.chain = a.chain and p.enabled = true
                      join custody_tenant_chain tc
                        on tc.tenant_id = ? and tc.chain = a.chain and tc.status = 'ACTIVE'
                      left join token_config t
                        on t.chain = a.chain and t.symbol = a.symbol and t.enabled = true
                       and lower(t.network) = lower(p.network)
                     where a.chain = ? and a.symbol = ? and a.active = true
                       and case when ? then p.scan_enabled
                                else p.withdraw_enabled and p.transfer_enabled end
                       and (a.native_asset = true or t.id is not null)
                )
                """, Boolean.class, tenantId, chain, symbol, deposit);
        return Boolean.TRUE.equals(enabled);
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ChainRecord(
            String chain,
            String network,
            String family,
            String nativeSymbol,
            boolean scanEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            String status,
            Instant openedAt,
            Instant closedAt,
            List<TokenRecord> tokens
    ) {
    }

    public record TokenRecord(
            String chain,
            String symbol,
            String standard,
            String contractAddress,
            int decimals,
            boolean platformEnabled
    ) {
    }
}
