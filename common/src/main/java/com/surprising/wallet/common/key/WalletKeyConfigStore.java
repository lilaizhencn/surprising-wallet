package com.surprising.wallet.common.key;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

public final class WalletKeyConfigStore {
    private final JdbcOperations jdbc;

    public WalletKeyConfigStore(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WalletKeyConfig> find() {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT sig1_seed, sig2_seed, recovery_seed, ed25519_seed,
                           created_at, updated_at, updated_by
                      FROM wallet_key_config
                     WHERE id = 1
                    """, (rs, rowNum) -> map(rs)));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public WalletKeyConfig require() {
        return find().orElseThrow(() -> new IllegalStateException("wallet keyset is not configured"));
    }

    public WalletKeyConfig save(WalletKeyConfig config, String actorId) {
        WalletSeedCodec.validate(config);
        jdbc.update("""
                INSERT INTO wallet_key_config
                    (id, sig1_seed, sig2_seed, recovery_seed, ed25519_seed, updated_by)
                VALUES (1, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    sig1_seed = EXCLUDED.sig1_seed,
                    sig2_seed = EXCLUDED.sig2_seed,
                    recovery_seed = EXCLUDED.recovery_seed,
                    ed25519_seed = EXCLUDED.ed25519_seed,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """, config.sig1Seed().trim(), config.sig2Seed().trim(),
                config.recoverySeed().trim(), config.ed25519Seed().trim(), actorId);
        return require();
    }

    public boolean hasDerivedAddresses() {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chain_address LIMIT 1)", Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    private static WalletKeyConfig map(ResultSet rs) throws SQLException {
        return new WalletKeyConfig(
                rs.getString("sig1_seed"),
                rs.getString("sig2_seed"),
                rs.getString("recovery_seed"),
                rs.getString("ed25519_seed"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("updated_by"));
    }
}
