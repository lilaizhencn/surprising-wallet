package com.surprising.wallet.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns only custody_hot_wallet_withdrawal_reservation. */
@Repository
public class CustodyHotWalletWithdrawalReservationRepository {
    private static final String COLUMNS = "id,tenant_id,wallet_id,chain,asset_symbol,order_no,source_address,amount,"
            + "source_observed_at,status,terminal_at,accounted_at,accounted_tx_hash,"
            + "accounted_receipt_block_height,accounted_receipt_block_hash,accounted_observed_block_height,"
            + "accounted_observed_block_hash,created_at";
    private final JdbcTemplate jdbc;

    public CustodyHotWalletWithdrawalReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Reservation(UUID id, UUID tenantId, UUID walletId, String chain, String assetSymbol,
                              String orderNo, String sourceAddress, BigDecimal amount, Instant sourceObservedAt,
                              String status, Instant terminalAt, Instant accountedAt,
                              String accountedTxHash, Long accountedReceiptBlockHeight,
                              String accountedReceiptBlockHash, Long accountedObservedBlockHeight,
                              String accountedObservedBlockHash, Instant createdAt) { }

    public int reserve(UUID id, UUID tenantId, UUID walletId, String chain, String asset,
                       String orderNo, String sourceAddress, BigDecimal amount, Instant observedAt) {
        return jdbc.update("""
                insert into custody_hot_wallet_withdrawal_reservation
                    (id,tenant_id,wallet_id,chain,asset_symbol,order_no,source_address,amount,source_observed_at)
                values (?,?,?,?,?,?,?,?,?)
                on conflict (tenant_id,chain,order_no) do nothing
                """, id, tenantId, walletId, chain, asset, orderNo, sourceAddress, amount,
                java.sql.Timestamp.from(observedAt));
    }

    public Optional<Reservation> findByOrder(UUID tenantId, String chain, String orderNo) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet_withdrawal_reservation "
                        + "where tenant_id=? and chain=? and order_no=?",
                CustodyHotWalletWithdrawalReservationRepository::map, tenantId, chain, orderNo)
                .stream().findFirst();
    }

    public Optional<Reservation> lockByOrder(UUID tenantId, String chain, String orderNo) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet_withdrawal_reservation "
                        + "where tenant_id=? and chain=? and order_no=? for update",
                CustodyHotWalletWithdrawalReservationRepository::map, tenantId, chain, orderNo)
                .stream().findFirst();
    }

    /** Confirmed principal remains charged until independent finalized receipt and balance are bound. */
    public BigDecimal committedExposure(UUID tenantId, UUID walletId, String asset) {
        return jdbc.queryForObject("""
                select coalesce(sum(amount),0) from custody_hot_wallet_withdrawal_reservation
                 where tenant_id=? and wallet_id=? and asset_symbol=?
                   and (status='OPEN' or (status='CONFIRMED' and accounted_at is null))
                """, BigDecimal.class, tenantId, walletId, asset);
    }

    /** Unresolved attempts count regardless of age; confirmed spend uses its finality time. */
    public BigDecimal rollingExposure(UUID tenantId, UUID walletId, String asset, Instant since) {
        return jdbc.queryForObject("""
                select coalesce(sum(amount),0) from custody_hot_wallet_withdrawal_reservation
                 where tenant_id=? and wallet_id=? and asset_symbol=?
                   and (status='OPEN' or (status='CONFIRMED' and terminal_at>=?))
                """, BigDecimal.class, tenantId, walletId, asset, java.sql.Timestamp.from(since));
    }

    public int markTerminal(UUID tenantId, String chain, String orderNo, String terminalStatus) {
        if (!java.util.Set.of("CONFIRMED", "FAILED", "CANCELLED").contains(terminalStatus)) {
            throw new IllegalArgumentException("invalid hot wallet reservation terminal status");
        }
        return jdbc.update("""
                update custody_hot_wallet_withdrawal_reservation
                   set status=?,terminal_at=now()
                 where tenant_id=? and chain=? and order_no=? and status='OPEN'
                """, terminalStatus, tenantId, chain, orderNo);
    }

    public int markAccounted(UUID tenantId, String chain, String orderNo, String txHash,
            long receiptHeight, String receiptHash, long observedHeight, String observedHash) {
        return jdbc.update("""
                update custody_hot_wallet_withdrawal_reservation
                   set accounted_at=now(),accounted_tx_hash=?,
                       accounted_receipt_block_height=?,accounted_receipt_block_hash=?,
                       accounted_observed_block_height=?,accounted_observed_block_hash=?
                 where tenant_id=? and chain=? and order_no=?
                   and status='CONFIRMED' and accounted_at is null
                """, txHash, receiptHeight, receiptHash, observedHeight, observedHash,
                tenantId, chain, orderNo);
    }

    public List<Reservation> listOpen(int limit) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet_withdrawal_reservation "
                        + "where status='OPEN' order by created_at,id limit ?",
                CustodyHotWalletWithdrawalReservationRepository::map, Math.min(Math.max(limit, 1), 500));
    }

    /** Cursor prevents permanently unprovable older spends from starving later ones. */
    public List<Reservation> listConfirmedUnaccountedAfter(UUID afterId, int limit) {
        return jdbc.query("select " + COLUMNS + " from custody_hot_wallet_withdrawal_reservation "
                        + "where status='CONFIRMED' and accounted_at is null and (?::uuid is null or id > ?) "
                        + "order by id limit ?",
                CustodyHotWalletWithdrawalReservationRepository::map, afterId, afterId,
                Math.min(Math.max(limit, 1), 100));
    }

    private static Reservation map(ResultSet rs, int row) throws SQLException {
        var terminal = rs.getTimestamp("terminal_at");
        var accounted = rs.getTimestamp("accounted_at");
        return new Reservation(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("wallet_id", UUID.class), rs.getString("chain"), rs.getString("asset_symbol"),
                rs.getString("order_no"), rs.getString("source_address"), rs.getBigDecimal("amount"),
                rs.getTimestamp("source_observed_at").toInstant(), rs.getString("status"),
                terminal == null ? null : terminal.toInstant(),
                accounted == null ? null : accounted.toInstant(),rs.getString("accounted_tx_hash"),
                rs.getObject("accounted_receipt_block_height",Long.class),
                rs.getString("accounted_receipt_block_hash"),
                rs.getObject("accounted_observed_block_height",Long.class),
                rs.getString("accounted_observed_block_hash"),rs.getTimestamp("created_at").toInstant());
    }
}
