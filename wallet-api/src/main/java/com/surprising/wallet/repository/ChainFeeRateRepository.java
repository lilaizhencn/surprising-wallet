package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** chain_fee_rate: network-wide fee quotes; not tenant funds. */
@Repository
public class ChainFeeRateRepository {
    private final JdbcTemplate jdbc;
    public ChainFeeRateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public String find(String chain) {
        return jdbc.query("select fee_rate from chain_fee_rate where chain = ?",
                (rs, n) -> rs.getString(1), chain).stream().findFirst().orElse(null);
    }
    public void save(String chain, String rate) {
        jdbc.update("""
                insert into chain_fee_rate(chain, fee_rate, updated_at) values (?, ?::bigint, now())
                on conflict (chain) do update set fee_rate = excluded.fee_rate, updated_at = now()
                """, chain, rate);
    }
}
