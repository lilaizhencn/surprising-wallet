package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** account_sequence 单表仓储。 */
@Repository
public class AccountSequenceRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造账户序列仓储。 */
    public AccountSequenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 原子预留下一个账户序列号。 */
    public long reserve(String chain, String address, long chainSequence) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into account_sequence(chain, address, chain_sequence, next_sequence, status, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (chain, address) do nothing
                """, chain, address, chainSequence, chainSequence, now, now);
        Long next = jdbc.queryForObject("""
                select next_sequence from account_sequence
                 where chain = ? and address = ? for update
                """, Long.class, chain, address);
        long reserved = Math.max(chainSequence, next == null ? chainSequence : next);
        jdbc.update("""
                update account_sequence
                   set chain_sequence = greatest(chain_sequence, ?), next_sequence = ?,
                       status = 'ACTIVE', updated_at = ?
                 where chain = ? and address = ?
                """, chainSequence, reserved + 1, Timestamp.from(Instant.now()), chain, address);
        return reserved;
    }

    /** 同步链上账户序列号。 */
    public void synchronize(String chain, String address, long chainSequence) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into account_sequence(chain, address, chain_sequence, next_sequence, status, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (chain, address) do update set
                    chain_sequence = excluded.chain_sequence,
                    next_sequence = greatest(account_sequence.next_sequence, excluded.next_sequence),
                    status = 'ACTIVE', updated_at = excluded.updated_at
                """, chain, address, chainSequence, chainSequence, now, now);
    }
}
