package com.surprising.wallet.custody.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class CustodyAssetDashboardRepository {
    private final JdbcTemplate jdbc;    public CustodyAssetDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public List<AssetBalance> balances(UUID tenantId) {
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
                       and not exists (select 1 from custody_gas_account g
                                        where g.tenant_id = c.tenant_id
                                          and g.custody_address_id = c.id)
                    union
                    select distinct c.id, c.tenant_id, base.chain, base.account_id
                      from custody_address c
                      join chain_address base
                        on base.tenant_id = c.tenant_id
                       and base.id = c.chain_address_id
                     where c.tenant_id = ?
                       and not exists (select 1 from custody_gas_account g
                                        where g.tenant_id = c.tenant_id
                                          and g.custody_address_id = c.id)
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
                    on lb.tenant_id = ta.tenant_id
                   and lb.chain = ta.chain and lower(lb.account_id) = lower(ta.account_id)
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
    public List<ReorgDeficit> openReorgDeficits(UUID tenantId) {
        return jdbc.query("""
                select id, custody_address_id, chain, asset_symbol,
                       deficit_amount, recovered_amount, created_at
                  from custody_reorg_deficit
                 where tenant_id = ? and status = 'OPEN'
                 order by created_at desc
                """, (rs, rowNum) -> new ReorgDeficit(
                rs.getObject("id", UUID.class),
                rs.getObject("custody_address_id", UUID.class),
                rs.getString("chain"), rs.getString("asset_symbol"),
                rs.getBigDecimal("deficit_amount"), rs.getBigDecimal("recovered_amount"),
                rs.getTimestamp("created_at").toInstant()), tenantId);
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

    public record ReorgDeficit(
            UUID id,
            UUID custodyAddressId,
            String chain,
            String assetSymbol,
            BigDecimal deficitAmount,
            BigDecimal recoveredAmount,
            Instant createdAt
    ) {
        public BigDecimal outstandingAmount() {
            return deficitAmount.subtract(recoveredAmount);
        }
    }
}
