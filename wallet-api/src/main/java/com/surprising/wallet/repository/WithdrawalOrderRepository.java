package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** withdrawal_order 单表仓储。 */
@Repository
public class WithdrawalOrderRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造提现订单仓储。 */
    public WithdrawalOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在提现订单。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from withdrawal_order where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在提现订单。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from withdrawal_order
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

}
