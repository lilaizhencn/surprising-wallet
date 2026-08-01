package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** evm_7702_config 单表仓储。 */
@Repository
public class Evm7702ConfigRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EIP-7702 配置仓储。 */
    public Evm7702ConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链和网络是否存在启用的 EIP-7702 配置。 */
    public boolean existsActive(String chain, String network) {
        return !jdbc.queryForList("""
                select id
                  from evm_7702_config
                 where lower(chain) = lower(?) and lower(network) = lower(?)
                   and status = 'ACTIVE'
                 limit 1
                """, chain, network).isEmpty();
    }
}
