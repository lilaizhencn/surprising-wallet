package com.surprising.wallet.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        if (chainSequence < 0 || chainSequence == Long.MAX_VALUE)
            throw new IllegalArgumentException("account sequence is outside the reservable range");
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                insert into account_sequence(chain, address, chain_sequence, next_sequence, status, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (chain, address) do update set
                    chain_sequence = greatest(account_sequence.chain_sequence, excluded.chain_sequence),
                    next_sequence = greatest(account_sequence.next_sequence, excluded.chain_sequence) + 1,
                    status = 'ACTIVE', updated_at = excluded.updated_at
                returning next_sequence - 1
                """, Long.class, chain, address, chainSequence, chainSequence + 1, now, now);
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
