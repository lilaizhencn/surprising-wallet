package com.surprising.wallet.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

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
    public boolean reserve(String keyId, String nonce, Timestamp expiresAt) {
        try {
            return jdbc.update("""
                    insert into custody_api_nonce(key_id, nonce, expires_at)
                    values (?, ?, ?)
                    """, keyId, nonce, expiresAt) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }
}
