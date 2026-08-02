package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.EvmNonceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;

/** evm_nonce 单表仓储。 */
@Repository
public class EvmNonceRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EVM nonce 仓储。 */
    public EvmNonceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入或更新链上 nonce。 */
    public int upsert(EvmNonceRecord record) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into evm_nonce(chain, address, chain_nonce, reserved_nonce, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, address) do update set
                    chain_nonce = greatest(evm_nonce.chain_nonce, excluded.chain_nonce),
                    reserved_nonce = excluded.reserved_nonce, status = excluded.status,
                    updated_at = excluded.updated_at
                """, record.getChain(), record.getAddress(), record.getChainNonce(),
                record.getReservedNonce(), record.getStatus(), now, now);
    }

    /** 原子预留 EVM nonce。 */
    public BigInteger reserve(String chain, String address, BigInteger chainNonce) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into evm_nonce(chain, address, chain_nonce, reserved_nonce, status, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (chain, address) do nothing
                """, chain, address, chainNonce, chainNonce, now, now);
        BigDecimal nextValue = jdbc.queryForObject("""
                select reserved_nonce from evm_nonce
                 where chain = ? and address = ? for update
                """, BigDecimal.class, chain, address);
        BigInteger next = nextValue == null ? chainNonce : nextValue.toBigIntegerExact();
        BigInteger reserved = chainNonce.max(next);
        jdbc.update("""
                update evm_nonce
                   set chain_nonce = greatest(chain_nonce, ?), reserved_nonce = ?,
                       status = 'ACTIVE', updated_at = ?
                 where chain = ? and address = ?
                """, chainNonce, reserved.add(BigInteger.ONE), Timestamp.from(Instant.now()), chain, address);
        return reserved;
    }
}
