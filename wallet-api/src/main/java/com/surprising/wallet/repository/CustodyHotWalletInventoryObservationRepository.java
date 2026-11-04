package com.surprising.wallet.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns only custody_hot_wallet_inventory_observation. No customer ledger mutation. */
@Repository
public class CustodyHotWalletInventoryObservationRepository {
    private static final String COLUMNS = "tenant_id,wallet_id,chain,asset_symbol,mpc_key_id,address,network_identity,"
            + "decimals,native_decimals,available_atomic,native_atomic,observed_at,collection_rule_id,"
            + "collection_rule_fingerprint,observed_block_height,observed_block_hash,invalidated_at";
    private final JdbcTemplate jdbc;
    public CustodyHotWalletInventoryObservationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Observation(UUID tenantId, UUID walletId, String chain, String assetSymbol,
                              UUID keyId, String address, String networkIdentity,
                              int decimals, int nativeDecimals, BigInteger availableAtomic,
                              BigInteger nativeAtomic, Instant observedAt, UUID collectionRuleId,
                              String collectionRuleFingerprint, Long observedBlockHeight,
                              String observedBlockHash,Instant invalidatedAt) {
        public Observation(UUID tenantId, UUID walletId, String chain, String assetSymbol,
                UUID keyId, String address, String networkIdentity,int decimals,int nativeDecimals,
                BigInteger availableAtomic,BigInteger nativeAtomic,Instant observedAt,
                UUID collectionRuleId,String collectionRuleFingerprint,Long observedBlockHeight,
                String observedBlockHash) {
            this(tenantId,walletId,chain,assetSymbol,keyId,address,networkIdentity,decimals,
                    nativeDecimals,availableAtomic,nativeAtomic,observedAt,collectionRuleId,
                    collectionRuleFingerprint,observedBlockHeight,observedBlockHash,null);
        }
    }

    public Optional<Observation> find(UUID tenantId,UUID walletId,String assetSymbol) {
        return jdbc.query("select "+COLUMNS+" from custody_hot_wallet_inventory_observation "
                        + "where tenant_id=? and wallet_id=? and asset_symbol=?",
                CustodyHotWalletInventoryObservationRepository::map,tenantId,walletId,assetSymbol).stream().findFirst();
    }

    /** A late RPC response cannot overwrite a newer observation or change its wallet identity. */
    public int record(Observation value) {
        return jdbc.update("""
                insert into custody_hot_wallet_inventory_observation
                    (tenant_id,wallet_id,chain,asset_symbol,mpc_key_id,address,network_identity,
                     decimals,native_decimals,available_atomic,native_atomic,observed_at,
                     collection_rule_id,collection_rule_fingerprint,observed_block_height,observed_block_hash)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (tenant_id,wallet_id,asset_symbol) do update set
                    available_atomic=excluded.available_atomic,native_atomic=excluded.native_atomic,
                    observed_at=excluded.observed_at,collection_rule_id=excluded.collection_rule_id,
                    collection_rule_fingerprint=excluded.collection_rule_fingerprint,
                    observed_block_height=excluded.observed_block_height,
                    observed_block_hash=excluded.observed_block_hash,invalidated_at=null,updated_at=now()
                 where custody_hot_wallet_inventory_observation.observed_at < excluded.observed_at
                   and custody_hot_wallet_inventory_observation.chain=excluded.chain
                   and custody_hot_wallet_inventory_observation.mpc_key_id=excluded.mpc_key_id
                   and custody_hot_wallet_inventory_observation.address=excluded.address
                   and custody_hot_wallet_inventory_observation.network_identity=excluded.network_identity
                   and custody_hot_wallet_inventory_observation.decimals=excluded.decimals
                   and custody_hot_wallet_inventory_observation.native_decimals=excluded.native_decimals
                   and (custody_hot_wallet_inventory_observation.observed_block_height is null
                        or excluded.observed_block_height >= custody_hot_wallet_inventory_observation.observed_block_height)
                   and (custody_hot_wallet_inventory_observation.observed_block_height is distinct from excluded.observed_block_height
                        or custody_hot_wallet_inventory_observation.observed_block_hash is not distinct from excluded.observed_block_hash)
                   and (custody_hot_wallet_inventory_observation.observed_block_height is distinct from excluded.observed_block_height
                        or (custody_hot_wallet_inventory_observation.available_atomic=excluded.available_atomic
                            and custody_hot_wallet_inventory_observation.native_atomic=excluded.native_atomic))
                """,value.tenantId(),value.walletId(),value.chain(),value.assetSymbol(),value.keyId(),
                value.address(),value.networkIdentity(),value.decimals(),value.nativeDecimals(),
                new BigDecimal(value.availableAtomic()),new BigDecimal(value.nativeAtomic()),
                Timestamp.from(value.observedAt()),value.collectionRuleId(),value.collectionRuleFingerprint(),
                value.observedBlockHeight(),value.observedBlockHash());
    }

    /** A failed earlier RPC cannot invalidate a newer successful observation. */
    public int invalidateIfUnchanged(UUID tenantId,UUID walletId,String assetSymbol,Instant observedAt) {
        return jdbc.update("""
                update custody_hot_wallet_inventory_observation
                   set invalidated_at=now(),updated_at=now()
                 where tenant_id=? and wallet_id=? and asset_symbol=?
                   and observed_at=? and invalidated_at is null
                """,tenantId,walletId,assetSymbol,Timestamp.from(observedAt));
    }

    /** The shared row lock serializes a previously signed broadcast with a failed refresh. */
    public boolean lockAllUninvalidated(UUID tenantId,UUID walletId) {
        var rows=jdbc.query("""
                select invalidated_at from custody_hot_wallet_inventory_observation
                 where tenant_id=? and wallet_id=? order by asset_symbol for share
                """,(rs,row)->rs.getTimestamp("invalidated_at"),tenantId,walletId);
        return !rows.isEmpty() && rows.stream().allMatch(java.util.Objects::isNull);
    }

    /** Transaction-free precheck; callers must repeat with row locks before committing authorization. */
    public boolean allUninvalidated(UUID tenantId,UUID walletId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)>0 and count(*) filter (where invalidated_at is not null)=0
                  from custody_hot_wallet_inventory_observation
                 where tenant_id=? and wallet_id=?
                """,Boolean.class,tenantId,walletId));
    }

    private static Observation map(ResultSet rs,int row) throws SQLException {
        return new Observation(rs.getObject("tenant_id",UUID.class),rs.getObject("wallet_id",UUID.class),
                rs.getString("chain"),rs.getString("asset_symbol"),rs.getObject("mpc_key_id",UUID.class),
                rs.getString("address"),rs.getString("network_identity"),rs.getInt("decimals"),
                rs.getInt("native_decimals"),rs.getBigDecimal("available_atomic").toBigIntegerExact(),
                rs.getBigDecimal("native_atomic").toBigIntegerExact(),rs.getTimestamp("observed_at").toInstant(),
                rs.getObject("collection_rule_id",UUID.class),rs.getString("collection_rule_fingerprint"),
                rs.getObject("observed_block_height",Long.class),rs.getString("observed_block_hash"),
                rs.getTimestamp("invalidated_at")==null?null:rs.getTimestamp("invalidated_at").toInstant());
    }
}
