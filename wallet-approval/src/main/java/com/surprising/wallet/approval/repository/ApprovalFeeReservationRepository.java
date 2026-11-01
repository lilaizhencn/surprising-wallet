package com.surprising.wallet.approval.repository;

import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.fee_reservation only. An unknown or failed signing attempt never releases reserved fee allowance. */
@Repository
public class ApprovalFeeReservationRepository {
    private final JdbcTemplate jdbc;
    public ApprovalFeeReservationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean differentDomain(UUID tenant,ApprovalAssetSnapshot asset,Instant now) {
        return !jdbc.queryForList("""
                select transaction_id from approval.fee_reservation where tenant_id=? and chain=? and network=? and created_at>=?
                and (network_identity<>? or native_symbol<>? or native_decimals<>?) limit 1
                """,UUID.class,tenant,asset.chain(),asset.network(),Timestamp.from(now.minusSeconds(86400)),asset.networkIdentity(),asset.nativeSymbol(),asset.nativeDecimals()).isEmpty();
    }
    public BigInteger rolling(UUID tenant,ApprovalAssetSnapshot asset,Instant now) {
        return jdbc.queryForObject("""
                select coalesce(sum(maximum_fee_atomic),0) from approval.fee_reservation
                where tenant_id=? and chain=? and network=? and created_at>=?
                """,BigDecimal.class,tenant,asset.chain(),asset.network(),Timestamp.from(now.minusSeconds(86400))).toBigIntegerExact();
    }
    public void insert(UUID tenant,UUID transaction,ApprovalAssetSnapshot asset,BigInteger fee,Instant now) {
        jdbc.update("""
                insert into approval.fee_reservation(tenant_id,transaction_id,chain,network,network_identity,native_symbol,native_decimals,maximum_fee_atomic,created_at)
                values (?,?,?,?,?,?,?,?,?)
                """,tenant,transaction,asset.chain(),asset.network(),asset.networkIdentity(),asset.nativeSymbol(),asset.nativeDecimals(),new BigDecimal(fee),Timestamp.from(now));
    }
    public BigInteger reserved(UUID tenant,UUID transaction) {
        return jdbc.queryForObject("select maximum_fee_atomic from approval.fee_reservation where tenant_id=? and transaction_id=?",BigDecimal.class,tenant,transaction).toBigIntegerExact();
    }
}
