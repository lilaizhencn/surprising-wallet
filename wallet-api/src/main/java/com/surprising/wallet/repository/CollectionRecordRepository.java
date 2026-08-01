package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** collection_record 单表仓储。 */
@Repository
public class CollectionRecordRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造归集记录仓储。 */
    public CollectionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在归集记录。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from collection_record where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在归集记录。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from collection_record
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

}
