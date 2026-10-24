package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** custody_api_nonce 单表仓储。 */
@Repository
public class CustodyApiNonceRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 API nonce 单表仓储。 */
    public CustodyApiNonceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 预留一次性 nonce。 */
    public boolean reserve(String keyId, String nonce, Instant expiresAt) {
        return jdbc.update("""
                    insert into custody_api_nonce(key_id, nonce, expires_at)
                    values (?, ?, ?)
                    on conflict (key_id, nonce) do nothing
                    """, keyId, nonce, Timestamp.from(expiresAt)) == 1;
    }

    /** 删除已过期的 API nonce。 */
    public int deleteExpired() {
        return jdbc.update("delete from custody_api_nonce where expires_at < now()");
    }
}
