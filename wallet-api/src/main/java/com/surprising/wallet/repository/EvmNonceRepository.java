package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.BigInteger;

/** evm_nonce 单表仓储。 */
@Repository
public class EvmNonceRepository {
    private static final BigDecimal MAX_NONCE = new BigDecimal(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EVM nonce 仓储。 */
    public EvmNonceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One EIP-7702 batch per concrete network protects authority operations and relayer ordering across job kinds. */
    public void lock7702Chain(String chain,String network) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("batch admission lock requires transaction");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",(rs,row)->true,
                "wallet-eip7702:"+chain+":"+network);
    }

    /** One statement allocates a nonce even when called without an enclosing transaction. */
    public BigInteger reserve(String chain, String address, BigInteger chainNonce) {
        if (chainNonce == null || chainNonce.signum() < 0 || chainNonce.bitLength() > 256) {
            throw new IllegalArgumentException("EVM nonce must be a valid uint256");
        }
        BigDecimal nonce = new BigDecimal(chainNonce);
        return jdbc.query("""
                insert into evm_nonce(chain, address, chain_nonce, reserved_nonce, status, created_at, updated_at)
                values (?, ?, ?, ? + 1, 'ACTIVE', now(), now())
                on conflict (chain, address) do update set
                    chain_nonce = greatest(evm_nonce.chain_nonce, excluded.chain_nonce),
                    reserved_nonce = greatest(evm_nonce.reserved_nonce, excluded.chain_nonce) + 1,
                    status = 'ACTIVE', updated_at = excluded.updated_at
                where greatest(evm_nonce.reserved_nonce, excluded.chain_nonce) <= ?
                returning reserved_nonce - 1
                """, (rs, rowNum) -> rs.getBigDecimal(1).toBigIntegerExact(), chain, address, nonce, nonce, MAX_NONCE)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("EVM nonce space exhausted"));
    }
}
