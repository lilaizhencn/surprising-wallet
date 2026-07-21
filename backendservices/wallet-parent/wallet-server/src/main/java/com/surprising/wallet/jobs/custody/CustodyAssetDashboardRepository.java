package com.surprising.wallet.jobs.custody;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class CustodyAssetDashboardRepository {
    private final JdbcTemplate jdbc;

    public CustodyAssetDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssetBalance> balances(UUID tenantId) {
        return jdbc.query("""
                with tenant_accounts as (
                    select distinct c.id as custody_address_id, related.chain, related.account_id
                      from custody_address c
                      join chain_address base on base.id = c.chain_address_id
                      join chain_address related
                        on related.chain = base.chain
                       and related.user_id = base.user_id
                       and related.biz = base.biz
                       and related.address_index = base.address_index
                       and related.wallet_role = base.wallet_role
                       and related.enabled = true
                     where c.tenant_id = ?
                       and not exists (select 1 from custody_gas_account g
                                        where g.custody_address_id = c.id)
                    union
                    select distinct c.id, base.chain, base.account_id
                      from custody_address c
                      join chain_address base on base.id = c.chain_address_id
                     where c.tenant_id = ?
                       and not exists (select 1 from custody_gas_account g
                                        where g.custody_address_id = c.id)
                ), configured_assets as (
                    select tc.chain, a.symbol as asset_symbol, true as native_asset
                      from custody_tenant_chain tc
                      join chain_profile p
                        on p.chain = tc.chain and p.enabled = true
                      join chain_asset a
                        on a.chain = tc.chain and a.active = true and a.native_asset = true
                     where tc.tenant_id = ? and tc.status = 'ACTIVE'
                    union all
                    select tc.chain, a.symbol, false
                      from custody_tenant_chain tc
                      join chain_profile p
                        on p.chain = tc.chain and p.enabled = true
                      join chain_asset a
                        on a.chain = tc.chain and a.active = true and a.native_asset = false
                      join token_config t
                        on t.chain = tc.chain and t.symbol = a.symbol
                       and lower(t.network) = lower(p.network)
                     where tc.tenant_id = ? and tc.status = 'ACTIVE'
                )
                select ca.chain, ca.asset_symbol, ca.native_asset,
                       coalesce(sum(lb.available_balance), 0) as available_balance,
                       coalesce(sum(lb.locked_balance), 0) as locked_balance,
                       coalesce(sum(lb.total_balance), 0) as total_balance,
                       count(distinct ta.custody_address_id)
                           filter (where lb.account_id is not null) as address_count,
                       p.usd_price, p.source as price_source, p.observed_at
                  from configured_assets ca
                  left join tenant_accounts ta on ta.chain = ca.chain
                  left join ledger_balance lb
                    on lb.chain = ta.chain and lower(lb.account_id) = lower(ta.account_id)
                   and lb.asset_symbol = ca.asset_symbol
                  left join custody_asset_price p on p.asset_symbol = ca.asset_symbol
                 group by ca.chain, ca.asset_symbol, ca.native_asset,
                          p.usd_price, p.source, p.observed_at
                 order by ca.native_asset desc, ca.asset_symbol, ca.chain
                """, (rs, rowNum) -> new AssetBalance(
                rs.getString("chain"), rs.getString("asset_symbol"),
                rs.getBoolean("native_asset"),
                rs.getBigDecimal("available_balance"), rs.getBigDecimal("locked_balance"),
                rs.getBigDecimal("total_balance"), rs.getLong("address_count"),
                rs.getBigDecimal("usd_price"), rs.getString("price_source"),
                instantOrNull(rs.getTimestamp("observed_at"))),
                tenantId, tenantId, tenantId, tenantId);
    }

    public List<AssetPrice> prices() {
        return jdbc.query("""
                select asset_symbol, usd_price, source, observed_at, updated_at
                  from custody_asset_price order by asset_symbol
                """, (rs, rowNum) -> new AssetPrice(
                rs.getString("asset_symbol"), rs.getBigDecimal("usd_price"),
                rs.getString("source"), rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()));
    }

    public AssetPrice upsertPrice(String symbol, BigDecimal price, String source, Instant observedAt) {
        return jdbc.queryForObject("""
                insert into custody_asset_price(asset_symbol, usd_price, source, observed_at, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (asset_symbol) do update set
                    usd_price = excluded.usd_price,
                    source = excluded.source,
                    observed_at = excluded.observed_at,
                    updated_at = now()
                returning asset_symbol, usd_price, source, observed_at, updated_at
                """, (rs, rowNum) -> new AssetPrice(
                rs.getString("asset_symbol"), rs.getBigDecimal("usd_price"),
                rs.getString("source"), rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
                symbol, price, source, Timestamp.from(observedAt));
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record AssetBalance(
            String chain,
            String assetSymbol,
            boolean nativeAsset,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            long addressCount,
            BigDecimal usdPrice,
            String priceSource,
            Instant priceObservedAt
    ) {
    }

    public record AssetPrice(
            String assetSymbol,
            BigDecimal usdPrice,
            String source,
            Instant observedAt,
            Instant updatedAt
    ) {
    }
}
