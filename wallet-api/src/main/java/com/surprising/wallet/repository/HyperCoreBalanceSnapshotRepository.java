package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** hypercore_balance_snapshot 单表仓储。 */
@Repository
public class HyperCoreBalanceSnapshotRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造余额快照仓储。 */
    public HyperCoreBalanceSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保余额快照行存在。 */
    public void ensure(String chain, String symbol, String accountId, String address,
                       String rawPayload, Timestamp now) {
        jdbc.update("""
                insert into hypercore_balance_snapshot(chain, asset_symbol, account_id, address,
                                                       observed_balance, raw_payload,
                                                       observed_at, created_at, updated_at)
                values (?, ?, ?, ?, 0, ?, ?, ?, ?)
                on conflict (chain, asset_symbol, account_id) do nothing
                """, chain, symbol, accountId, address, rawPayload, now, now, now);
    }

    /** 锁定并读取当前余额快照。 */
    public BigDecimal findForUpdate(String chain, String symbol, String accountId) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select observed_balance
                  from hypercore_balance_snapshot
                 where chain = ? and asset_symbol = ? and account_id = ?
                 for update
                """, BigDecimal.class, chain, symbol, accountId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst();
    }

    /** 保存当前余额快照。 */
    public void upsert(String chain, String symbol, String accountId, String address,
                       BigDecimal observed, String rawPayload, Timestamp now) {
        jdbc.update("""
                insert into hypercore_balance_snapshot(chain, asset_symbol, account_id, address,
                                                       observed_balance, raw_payload,
                                                       observed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, asset_symbol, account_id) do update set
                    address = excluded.address,
                    observed_balance = excluded.observed_balance,
                    raw_payload = excluded.raw_payload,
                    observed_at = excluded.observed_at,
                    updated_at = excluded.updated_at
                """, chain, symbol, accountId, address, observed, rawPayload, now, now, now);
    }
}
