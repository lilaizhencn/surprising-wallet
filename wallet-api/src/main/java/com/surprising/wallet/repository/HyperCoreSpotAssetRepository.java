package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** hypercore_spot_asset 单表仓储。 */
@Repository
public class HyperCoreSpotAssetRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 HyperCore 现货资产仓储。 */
    public HyperCoreSpotAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存现货资产元数据。 */
    public void upsert(String network, Integer spotIndex, String name,
                       Integer baseTokenIndex, Integer quoteTokenIndex, Boolean canonical,
                       java.sql.Timestamp now) {
        jdbc.update("""
                insert into hypercore_spot_asset(network, spot_index, name, base_token_index,
                                                 quote_token_index, is_canonical, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (network, spot_index) do update set
                    name = excluded.name,
                    base_token_index = excluded.base_token_index,
                    quote_token_index = excluded.quote_token_index,
                    is_canonical = excluded.is_canonical,
                    updated_at = excluded.updated_at
                """, network, spotIndex, name, baseTokenIndex, quoteTokenIndex,
                Boolean.TRUE.equals(canonical), now, now);
    }
}
