package com.surprising.wallet.jobs.custody;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class CustodyTenantChainRepository {
    private final JdbcTemplate jdbc;

    public CustodyTenantChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChainRecord> list(UUID tenantId) {
        return jdbc.query("""
                        select p.chain, p.network, p.family, p.native_symbol,
                               p.scan_enabled, p.withdraw_enabled, p.transfer_enabled,
                               coalesce(tc.status, 'CLOSED') as tenant_status,
                               tc.opened_at, tc.closed_at,
                               coalesce(array_agg(a.symbol order by a.native_asset desc, a.symbol)
                                   filter (where a.symbol is not null), array[]::varchar[]) as asset_symbols
                          from chain_profile p
                          left join custody_tenant_chain tc
                            on tc.tenant_id = ? and tc.chain = p.chain
                          left join chain_asset a
                            on a.chain = p.chain and a.active = true
                         where p.enabled = true
                         group by p.chain, p.network, p.family, p.native_symbol,
                                  p.scan_enabled, p.withdraw_enabled, p.transfer_enabled,
                                  tc.status, tc.opened_at, tc.closed_at
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
                arrayValues(rs.getArray("asset_symbols").getArray())), tenantId);
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

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static List<String> arrayValues(Object value) {
        return value instanceof Object[] values
                ? Arrays.stream(values).map(Object::toString).toList()
                : List.of();
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
            List<String> assetSymbols
    ) {
    }
}
