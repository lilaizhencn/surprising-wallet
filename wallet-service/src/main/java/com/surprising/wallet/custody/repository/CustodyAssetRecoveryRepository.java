package com.surprising.wallet.custody.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.surprising.wallet.custody.gateway.CustodyAssetRecoveryChainGateway;

@Repository
public class CustodyAssetRecoveryRepository {
    private final JdbcTemplate jdbc;
    public CustodyAssetRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RecoveryRecord insert(UUID id, UUID tenantId, String actualChain, String expectedChain,
                                 String assetSymbol, String tokenContract, String txHash,
                                 long logIndex, Long requestedLogIndex, String destinationAddress,
                                 BigDecimal claimedAmount,
                                 UUID requestedBy) {
        jdbc.update("""
                        insert into custody_asset_recovery(
                            id, tenant_id, actual_chain, expected_chain, asset_symbol,
                            token_contract, tx_hash, log_index, requested_log_index, destination_address,
                            claimed_amount, requested_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, tenantId, actualChain, expectedChain, assetSymbol,
                tokenContract, txHash, logIndex, requestedLogIndex,
                destinationAddress, claimedAmount, requestedBy);
        return require(id);
    }
    public RecoveryRecord require(UUID id) {
        return jdbc.query("""
                        select * from custody_asset_recovery where id = ?
                        """, this::map, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("asset recovery case not found"));
    }
    public RecoveryRecord require(UUID tenantId, UUID id) {
        return jdbc.query("""
                        select * from custody_asset_recovery where tenant_id = ? and id = ?
                        """, this::map, tenantId, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("asset recovery case not found"));
    }
    public Optional<RecoveryRecord> findByTransaction(String actualChain, String txHash, long logIndex) {
        return jdbc.query("""
                        select * from custody_asset_recovery
                         where actual_chain = ? and lower(tx_hash) = lower(?) and log_index = ?
                        """, this::map, actualChain, txHash, logIndex).stream().findFirst();
    }

    public Optional<RecoveryRecord> findByRequest(String actualChain, String txHash,
                                                  String destinationAddress, String assetSymbol,
                                                  String tokenContract) {
        return jdbc.query("""
                        select * from custody_asset_recovery
                         where actual_chain = ? and lower(tx_hash) = lower(?)
                           and lower(destination_address) = lower(?) and asset_symbol = ?
                           and ((token_contract is null and ? is null)
                                or lower(token_contract) = lower(?))
                         order by created_at desc
                         limit 1
                        """, this::map, actualChain, txHash, destinationAddress,
                assetSymbol, tokenContract, tokenContract).stream().findFirst();
    }
    public List<RecoveryRecord> list(UUID tenantId, String status, int limit, int offset) {
        if (tenantId == null) {
            return jdbc.query("""
                            select * from custody_asset_recovery
                             where (? = '' or status = ?)
                             order by created_at desc limit ? offset ?
                            """, this::map, status, status, limit, offset);
        }
        return jdbc.query("""
                        select * from custody_asset_recovery
                         where tenant_id = ? and (? = '' or status = ?)
                         order by created_at desc limit ? offset ?
                        """, this::map, tenantId, status, status, limit, offset);
    }

    public RecoveryRecord verified(UUID id, UUID custodyAddressId,
                                   CustodyAssetRecoveryChainGateway.Verification verification) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set custody_address_id = ?, token_contract = ?, token_decimals = ?,
                               log_index = ?, verified_amount = ?, block_height = ?, block_hash = ?,
                               confirmations = ?, verification_details = cast(? as jsonb),
                               status = 'VERIFIED', failure_reason = null, updated_at = now()
                         where id = ? and status in ('SUBMITTED', 'VERIFIED')
                        """, custodyAddressId, verification.tokenContract(), verification.tokenDecimals(),
                verification.logIndex(), verification.amount(), verification.blockHeight(),
                verification.blockHash(), verification.confirmations(), verification.detailsJson(), id) != 1) {
            throw new IllegalStateException("asset recovery case cannot be verified in its current state");
        }
        return require(id);
    }
    public RecoveryRecord verificationFailed(UUID id, String reason) {
        jdbc.update("""
                        update custody_asset_recovery
                           set failure_reason = ?, updated_at = now()
                         where id = ? and status = 'SUBMITTED'
                        """, reason, id);
        return require(id);
    }
    public RecoveryRecord approve(UUID id, String recoveryAddress, UUID reviewer) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set recovery_address = ?, reviewed_by = ?, approved_at = now(),
                               status = 'APPROVED', failure_reason = null, updated_at = now()
                         where id = ? and status = 'VERIFIED'
                        """, recoveryAddress, reviewer, id) != 1) {
            throw new IllegalStateException("only a verified recovery case can be approved");
        }
        return require(id);
    }
    public boolean claimExecution(UUID id) {
        return jdbc.update("""
                        update custody_asset_recovery
                           set status = 'EXECUTING', failure_reason = null, updated_at = now()
                         where id = ? and status = 'APPROVED'
                        """, id) == 1;
    }
    public RecoveryRecord broadcasted(UUID id, String recoveryTxHash, UUID executor) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set recovery_tx_hash = ?, executed_by = ?,
                               status = 'BROADCAST', failure_reason = null, updated_at = now()
                         where id = ? and status = 'EXECUTING'
                        """, recoveryTxHash, executor, id) != 1) {
            throw new IllegalStateException("asset recovery execution state changed unexpectedly");
        }
        return require(id);
    }
    public RecoveryRecord confirmed(UUID id) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set status = 'RECOVERED', executed_at = now(),
                               failure_reason = null, updated_at = now()
                         where id = ? and status = 'BROADCAST'
                        """, id) != 1) {
            throw new IllegalStateException("only a broadcast recovery can be confirmed");
        }
        return require(id);
    }
    public RecoveryRecord broadcastFailed(UUID id, String reason) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set status = 'APPROVED', failure_reason = ?, updated_at = now()
                         where id = ? and status = 'BROADCAST'
                        """, reason, id) != 1) {
            throw new IllegalStateException("only a broadcast recovery can be marked failed");
        }
        return require(id);
    }
    public List<RecoveryRecord> broadcastRecoveries(int limit) {
        return jdbc.query("""
                        select * from custody_asset_recovery
                         where status = 'BROADCAST'
                         order by updated_at limit ?
                        """, this::map, limit);
    }
    public RecoveryRecord executionFailed(UUID id, String reason) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set status = 'APPROVED', failure_reason = ?, updated_at = now()
                         where id = ? and status = 'EXECUTING'
                        """, reason, id) != 1) {
            throw new IllegalStateException("asset recovery execution state changed unexpectedly");
        }
        return require(id);
    }
    public RecoveryRecord reject(UUID id, String reason, UUID reviewer) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set status = 'REJECTED', failure_reason = ?, reviewed_by = ?, updated_at = now()
                         where id = ? and status in ('SUBMITTED', 'VERIFIED')
                        """, reason, reviewer, id) != 1) {
            throw new IllegalStateException("asset recovery case cannot be rejected in its current state");
        }
        return require(id);
    }
    public RecoveryRecord cancel(UUID tenantId, UUID id) {
        if (jdbc.update("""
                        update custody_asset_recovery
                           set status = 'CANCELLED', updated_at = now()
                         where tenant_id = ? and id = ? and status in ('SUBMITTED', 'VERIFIED')
                        """, tenantId, id) != 1) {
            throw new IllegalStateException("asset recovery case cannot be cancelled in its current state");
        }
        return require(tenantId, id);
    }
    private RecoveryRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new RecoveryRecord(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class), rs.getString("actual_chain"),
                rs.getString("expected_chain"), rs.getString("asset_symbol"),
                rs.getString("token_contract"), (Integer) rs.getObject("token_decimals"),
                rs.getString("tx_hash"), rs.getLong("log_index"),
                (Long) rs.getObject("requested_log_index"),
                rs.getString("destination_address"), rs.getString("recovery_address"),
                rs.getBigDecimal("claimed_amount"), rs.getBigDecimal("verified_amount"),
                (Long) rs.getObject("block_height"), rs.getString("block_hash"),
                rs.getInt("confirmations"), rs.getString("status"),
                rs.getString("verification_details"), rs.getString("failure_reason"),
                rs.getString("recovery_tx_hash"), rs.getObject("requested_by", UUID.class),
                rs.getObject("reviewed_by", UUID.class), rs.getObject("executed_by", UUID.class),
                instant(rs, "approved_at"), instant(rs, "executed_at"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    public record RecoveryRecord(
            UUID id, UUID tenantId, UUID custodyAddressId, String actualChain,
            String expectedChain, String assetSymbol, String tokenContract,
            Integer tokenDecimals, String txHash, long logIndex, Long requestedLogIndex,
            String destinationAddress,
            String recoveryAddress, BigDecimal claimedAmount, BigDecimal verifiedAmount,
            Long blockHeight, String blockHash, int confirmations, String status,
            String verificationDetails, String failureReason, String recoveryTxHash,
            UUID requestedBy, UUID reviewedBy, UUID executedBy, Instant approvedAt,
            Instant executedAt, Instant createdAt, Instant updatedAt) {
    }
}
