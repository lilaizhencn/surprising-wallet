package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

/** custody_asset_price 单表仓储。 */
@Repository
public class CustodyAssetPriceRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造资产价格仓储。 */
    public CustodyAssetPriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询全部资产价格。 */
    public List<PriceRow> list() {
        return jdbc.query("""
                select asset_symbol, usd_price, source, observed_at, updated_at
                  from custody_asset_price
                 order by asset_symbol
                """, (rs, rowNum) -> toPriceRow(rs.getString("asset_symbol"),
                rs.getBigDecimal("usd_price"), rs.getString("source"),
                rs.getTimestamp("observed_at"), rs.getTimestamp("updated_at")));
    }

    /** 新增或更新资产价格。 */
    public PriceRow upsert(String symbol, BigDecimal price, String source, Instant observedAt) {
        return jdbc.queryForObject("""
                insert into custody_asset_price(asset_symbol, usd_price, source, observed_at, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (asset_symbol) do update set
                    usd_price = excluded.usd_price,
                    source = excluded.source,
                    observed_at = excluded.observed_at,
                    updated_at = now()
                returning asset_symbol, usd_price, source, observed_at, updated_at
                """, (rs, rowNum) -> toPriceRow(rs.getString("asset_symbol"),
                rs.getBigDecimal("usd_price"), rs.getString("source"),
                rs.getTimestamp("observed_at"), rs.getTimestamp("updated_at")),
                symbol, price, source, Timestamp.from(observedAt));
    }

    /** 组装资产价格记录。 */
    private static PriceRow toPriceRow(String symbol, BigDecimal price, String source,
                                       Timestamp observedAt, Timestamp updatedAt) {
        return new PriceRow(symbol, price, source,
                observedAt == null ? null : observedAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant());
    }

    /** 资产价格数据库记录。 */
    public record PriceRow(
            String assetSymbol,
            BigDecimal usdPrice,
            String source,
            Instant observedAt,
            Instant updatedAt
    ) {
    }
}
