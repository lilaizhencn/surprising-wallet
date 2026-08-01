package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** deposit_record 单表仓储。 */
@Repository
public class DepositRecordRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造充值记录仓储。 */
    public DepositRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在充值记录。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from deposit_record where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在充值记录。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from deposit_record
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

    /** 判断交易日志是否已经被充值记录以 canonical 状态处理。 */
    public boolean existsCanonical(String chain, String txHash, long logIndex) {
        return !jdbc.queryForList("""
                select id from deposit_record
                 where chain = ? and lower(tx_hash) = lower(?) and log_index = ?
                   and canonical_status = 'CANONICAL'
                 limit 1
                """, chain, txHash, logIndex).isEmpty();
    }

}
