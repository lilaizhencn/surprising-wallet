package com.surprising.wallet.jobs.custody;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class CustodyTenantChainRepository {
    private final JdbcTemplate jdbc;

    public CustodyTenantChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChainRecord> list(UUID tenantId) {
        Map<String, List<TokenRecord>> tokensByChain = availableTokens(tenantId).stream()
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

    public List<TokenRecord> availableTokens(UUID tenantId) {
        return jdbc.query("""
                select p.chain, a.symbol,
                       coalesce(t.token_standard, t.standard) as standard,
                       t.contract_address, t.decimals,
                       t.enabled as platform_enabled,
                       coalesce(tt.enabled, false) as tenant_enabled,
                       coalesce(tt.deposit_enabled, false) and t.enabled as deposit_enabled,
                       coalesce(tt.withdrawal_enabled, false) and t.enabled as withdrawal_enabled
                  from chain_profile p
                  join chain_asset a
                    on a.chain = p.chain and a.active = true and a.native_asset = false
                  join token_config t
                    on t.chain = p.chain and t.symbol = a.symbol
                   and lower(t.network) = lower(p.network)
                  left join custody_tenant_token tt
                    on tt.tenant_id = ? and tt.chain = p.chain and tt.symbol = a.symbol
                 where p.enabled = true
                 order by p.chain, a.symbol
                """, (rs, rowNum) -> new TokenRecord(
                rs.getString("chain"), rs.getString("symbol"), rs.getString("standard"),
                rs.getString("contract_address"), rs.getInt("decimals"),
                rs.getBoolean("platform_enabled"),
                rs.getBoolean("tenant_enabled"), rs.getBoolean("deposit_enabled"),
                rs.getBoolean("withdrawal_enabled")), tenantId);
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

    public boolean tokenAvailable(String chain, String symbol) {
        Boolean available = jdbc.queryForObject("""
                select exists(
                    select 1
                      from chain_profile p
                      join chain_asset a
                        on a.chain = p.chain and a.symbol = ?
                       and a.active = true and a.native_asset = false
                      join token_config t
                        on t.chain = p.chain and t.symbol = a.symbol and t.enabled = true
                       and lower(t.network) = lower(p.network)
                     where p.chain = ? and p.enabled = true
                )
                """, Boolean.class, symbol, chain);
        return Boolean.TRUE.equals(available);
    }

    public boolean tokenConfigured(String chain, String symbol) {
        Boolean configured = jdbc.queryForObject("""
                select exists(
                    select 1
                      from chain_profile p
                      join chain_asset a
                        on a.chain = p.chain and a.symbol = ?
                       and a.active = true and a.native_asset = false
                      join token_config t
                        on t.chain = p.chain and t.symbol = a.symbol
                       and lower(t.network) = lower(p.network)
                     where p.chain = ? and p.enabled = true
                )
                """, Boolean.class, symbol, chain);
        return Boolean.TRUE.equals(configured);
    }

    public void setTokenSettings(UUID tenantId, String chain, String symbol,
                                 boolean enabled, boolean depositEnabled,
                                 boolean withdrawalEnabled, UUID actorId) {
        jdbc.update("""
                insert into custody_tenant_token(
                    tenant_id, chain, symbol, enabled, deposit_enabled,
                    withdrawal_enabled, updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id, chain, symbol) do update set
                    enabled = excluded.enabled,
                    deposit_enabled = excluded.deposit_enabled,
                    withdrawal_enabled = excluded.withdrawal_enabled,
                    updated_by = excluded.updated_by,
                    updated_at = now()
                """, tenantId, chain, symbol, enabled, depositEnabled,
                withdrawalEnabled, actorId);
    }

    public boolean depositEnabled(UUID tenantId, String chain, String symbol) {
        return assetOperationEnabled(tenantId, chain, symbol, "deposit_enabled");
    }

    public boolean withdrawalEnabled(UUID tenantId, String chain, String symbol) {
        return assetOperationEnabled(tenantId, chain, symbol, "withdrawal_enabled");
    }

    private boolean assetOperationEnabled(UUID tenantId, String chain, String symbol,
                                          String operationColumn) {
        if (!Set.of("deposit_enabled", "withdrawal_enabled").contains(operationColumn)) {
            throw new IllegalArgumentException("unsupported tenant token operation");
        }
        Boolean enabled = jdbc.queryForObject("""
                select exists(
                    select 1
                      from chain_asset a
                      join chain_profile p
                        on p.chain = a.chain and p.enabled = true
                      join custody_tenant_chain tc
                        on tc.tenant_id = ? and tc.chain = a.chain and tc.status = 'ACTIVE'
                      left join custody_tenant_token tt
                        on tt.tenant_id = tc.tenant_id
                       and tt.chain = a.chain and tt.symbol = a.symbol
                      left join token_config t
                        on t.chain = a.chain and t.symbol = a.symbol and t.enabled = true
                       and lower(t.network) = lower(p.network)
                     where a.chain = ? and a.symbol = ? and a.active = true
                       and (a.native_asset = true
                            or (tt.enabled = true and %s = true and t.id is not null))
                )
                """.formatted("tt." + operationColumn), Boolean.class,
                tenantId, chain, symbol);
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
            boolean platformEnabled,
            boolean enabled,
            boolean depositEnabled,
            boolean withdrawalEnabled
    ) {
    }
}
