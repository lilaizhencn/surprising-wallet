package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.CardanoTransactionRecord;
import com.surprising.wallet.common.chain.ChainScanPoint;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** cardano_transaction only: immutable admission, selected CBOR, bounded proof search and actual expenses. */
@Repository
@RequiredArgsConstructor
public class CardanoTransactionRepository {
    private final JdbcTemplate jdbc;

    public int insertPending(UUID requestId, UUID tenantId, String sender, String hash, ChainScanPoint observed) {
        return jdbc.update("""
                insert into cardano_transaction(request_id,tenant_id,sender,tx_hash,status,
                    observed_height,observed_slot,observed_hash,search_height,search_slot,search_hash)
                values (?,?,?,?,'SIGNING',?,?,?,?,?,?) on conflict(request_id) do nothing
                """, requestId, tenantId, sender, hash, observed.height(), observed.slot(), observed.hash(),
                observed.height(), observed.slot(), observed.hash());
    }

    public Optional<CardanoTransactionRecord> find(UUID tenantId, UUID requestId) {
        return jdbc.query("select * from cardano_transaction where tenant_id=? and request_id=?",
                CardanoTransactionRepository::map, tenantId, requestId).stream().findFirst();
    }

    public Optional<CardanoTransactionRecord> findByHash(UUID tenantId, String hash) {
        return jdbc.query("select * from cardano_transaction where tenant_id=? and tx_hash=?",
                CardanoTransactionRepository::map, tenantId, hash).stream().findFirst();
    }

    public boolean hasPendingSender(String sender) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from cardano_transaction where sender=?
                    and status in ('SIGNING','PREPARED','SENT','CONFIRMING'))
                """, Boolean.class, sender));
    }

    public long lastTerminalHeight(String sender) {
        return jdbc.queryForObject("""
                select coalesce(max(block_height),0) from cardano_transaction
                 where sender=? and status in ('CONFIRMED','FAILED')
                """, Long.class, sender);
    }

    public int bindSigned(UUID tenantId, UUID requestId, String hash, String raw) {
        return jdbc.update("""
                update cardano_transaction set raw_payload=?,status='PREPARED',updated_at=now()
                 where tenant_id=? and request_id=? and tx_hash=? and raw_payload is null and status='SIGNING'
                """, raw, tenantId, requestId, hash);
    }

    public int markSubmitted(UUID tenantId, UUID requestId) {
        return jdbc.update("""
                update cardano_transaction set status='SENT',updated_at=now()
                 where tenant_id=? and request_id=? and raw_payload is not null and status in ('PREPARED','SENT')
                """, tenantId, requestId);
    }

    public int advanceSearch(UUID tenantId, UUID requestId, ChainScanPoint expected, ChainScanPoint next) {
        if (next.height() < expected.height() || next.slot() < expected.slot()
                || (next.height() == expected.height() && !next.equals(expected)))
            throw new IllegalArgumentException("Cardano proof search cannot change or rewind its intersection");
        return jdbc.update("""
                update cardano_transaction set search_height=?,search_slot=?,search_hash=?,updated_at=now()
                 where tenant_id=? and request_id=? and status in ('PREPARED','SENT','CONFIRMING')
                   and execution_receipt is null and search_height=? and search_slot=? and search_hash=?
                   and search_height<=? and search_slot<=?
                """, next.height(), next.slot(), next.hash(), tenantId, requestId,
                expected.height(), expected.slot(), expected.hash(), next.height(), next.slot());
    }

    public int recordProof(UUID tenantId, UUID requestId, String proof, BigInteger fee, BigInteger expense,
                           long height, String status) {
        return jdbc.update("""
                update cardano_transaction set execution_receipt=coalesce(execution_receipt,?::jsonb),
                    fee_lovelace=?,native_expense_lovelace=?,block_height=coalesce(block_height,?),status=?,updated_at=now()
                 where tenant_id=? and request_id=? and raw_payload is not null
                   and status in ('PREPARED','SENT','CONFIRMING')
                   and (execution_receipt is null or (execution_receipt=?::jsonb and fee_lovelace=?
                        and native_expense_lovelace=? and block_height=?))
                """, proof, new BigDecimal(fee), new BigDecimal(expense), height, status, tenantId, requestId,
                proof, new BigDecimal(fee), new BigDecimal(expense), height);
    }

    public Optional<BigDecimal> findConfirmedExpense(String hash) {
        return jdbc.query("""
                select native_expense_lovelace from cardano_transaction where tx_hash=?
                 and status in ('CONFIRMED','FAILED') and execution_receipt is not null
                """, (row, index) -> row.getBigDecimal("native_expense_lovelace").movePointLeft(6), hash).stream().findFirst();
    }

    private static CardanoTransactionRecord map(ResultSet row, int index) throws SQLException {
        var fee = row.getBigDecimal("fee_lovelace"); var expense = row.getBigDecimal("native_expense_lovelace");
        return new CardanoTransactionRecord(row.getObject("request_id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getString("sender"), row.getString("tx_hash"), row.getString("status"), row.getString("raw_payload"),
                new ChainScanPoint(row.getLong("observed_height"), row.getLong("observed_slot"), row.getString("observed_hash")),
                new ChainScanPoint(row.getLong("search_height"), row.getLong("search_slot"), row.getString("search_hash")),
                fee == null ? null : fee.toBigIntegerExact(), expense == null ? null : expense.toBigIntegerExact(),
                row.getObject("block_height", Long.class), row.getString("execution_receipt"));
    }
}
