package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** custody_tenant_chain 单表仓储。 */
@Repository
public class CustodyTenantChainTableRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造租户链单表仓储。 */
    public CustodyTenantChainTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询租户的链状态记录。 */
    public List<ChainRow> list(UUID tenantId) {
        return jdbc.query("""
                select chain, status, withdrawal_paused, opened_at, closed_at
                  from custody_tenant_chain
                 where tenant_id = ?
                """, (rs, rowNum) -> new ChainRow(
                rs.getString("chain"), rs.getString("status"), rs.getBoolean("withdrawal_paused"),
                instant(rs.getTimestamp("opened_at")), instant(rs.getTimestamp("closed_at"))), tenantId);
    }

    /** 判断租户是否已启用指定链。 */
    public boolean active(UUID tenantId, String chain) {
        return !jdbc.queryForList("""
                select chain
                  from custody_tenant_chain
                 where tenant_id = ? and upper(chain) = upper(?) and status = 'ACTIVE'
                """, tenantId, chain).isEmpty();
    }

    public boolean withdrawalPaused(UUID tenantId, String chain) {
        return Boolean.TRUE.equals(jdbc.query("""
                select withdrawal_paused from custody_tenant_chain
                 where tenant_id = ? and upper(chain) = upper(?)
                """, rs -> rs.next() && rs.getBoolean(1), tenantId, chain));
    }

    /** Serializes final authorization against a concurrent emergency pause. */
    public boolean withdrawalPausedForSigning(UUID tenantId, String chain) {
        return Boolean.TRUE.equals(jdbc.query("""
                select withdrawal_paused from custody_tenant_chain
                 where tenant_id = ? and upper(chain) = upper(?) for share
                """, rs -> !rs.next() || rs.getBoolean(1), tenantId, chain));
    }

    /** Source routing and route activation share this tenant-chain lock. */
    public boolean lockWithdrawalRoutingEnabled(UUID tenantId, String chain) {
        return Boolean.TRUE.equals(jdbc.query("""
                select status,withdrawal_paused from custody_tenant_chain
                 where tenant_id=? and upper(chain)=upper(?) for share
                """, rs -> rs.next() && "ACTIVE".equals(rs.getString(1)) && !rs.getBoolean(2),
                tenantId, chain));
    }

    /** Collection may target an already active safe wallet while withdrawals remain paused. */
    public boolean lockCollectionRoutingOpen(UUID tenantId, String chain) {
        return Boolean.TRUE.equals(jdbc.query("""
                select status from custody_tenant_chain
                 where tenant_id=? and upper(chain)=upper(?) for share
                """, rs -> rs.next() && "ACTIVE".equals(rs.getString(1)), tenantId, chain));
    }

    /** Legacy UTXO broadcasts have no hot-wallet route; a configured emergency pause stops all of them. */
    public boolean lockLegacyBroadcastAllowed(UUID tenantId, String chain) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("legacy broadcast lock requires one database transaction");
        lockLegacyAdvisory(tenantId,chain,true);
        return Boolean.TRUE.equals(jdbc.query("""
                select status,withdrawal_paused from custody_tenant_chain
                 where tenant_id=? and upper(chain)=upper(?) for share
                """, rs -> !rs.next() || ("ACTIVE".equals(rs.getString(1)) && !rs.getBoolean(2)),
                tenantId, chain));
    }

    /** A separate connection keeps the row lock while native XMR relay remains outside a Spring transaction. */
    public ExternalBroadcastLease lockExternalLegacyBroadcast(UUID tenantId,String chain) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("native relay lock requires committed business state");
        Connection connection=null;
        try {
            connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            boolean autoCommit=connection.getAutoCommit();
            connection.setAutoCommit(false);
            var lease=new ExternalBroadcastLease(connection,autoCommit);
            try(PreparedStatement advisory=connection.prepareStatement("""
                    select pg_advisory_xact_lock_shared(hashtextextended(?::text,0))
                    """)) {
                advisory.setString(1,legacyLockKey(tenantId,chain));
                advisory.execute();
            }
            try(PreparedStatement statement=connection.prepareStatement("""
                    select status,withdrawal_paused from custody_tenant_chain
                     where tenant_id=? and upper(chain)=upper(?) for share
                    """)) {
                statement.setObject(1,tenantId);statement.setString(2,chain);
                try(ResultSet row=statement.executeQuery()) {
                    if(row.next() && (!"ACTIVE".equals(row.getString(1)) || row.getBoolean(2)))
                        throw new IllegalStateException("tenant native relay is emergency paused");
                }
            } catch(SQLException error) {
                lease.close();throw new IllegalStateException("native relay lock query failed",error);
            } catch(RuntimeException error) {
                lease.close();throw error;
            }
            return lease;
        } catch(SQLException error) {
            if(connection!=null)try{connection.close();}catch(SQLException close){error.addSuppressed(close);}
            throw new IllegalStateException("native relay lock unavailable",error);
        }
    }

    /** Records an emergency stop without accidentally opening an unconfigured legacy tenant-chain. */
    public int ensureEmergencyPaused(UUID tenantId,String chain,UUID actorId) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("emergency pause requires one committed transaction");
        lockLegacyAdvisory(tenantId,chain,false);
        return jdbc.update("""
                insert into custody_tenant_chain(tenant_id,chain,status,closed_by,closed_at,
                                                 withdrawal_paused,withdrawal_paused_at,withdrawal_paused_by)
                values (?,?,'CLOSED',?,now(),true,now(),?)
                on conflict(tenant_id,chain) do update set
                    withdrawal_paused=true,
                    withdrawal_paused_at=case when custody_tenant_chain.withdrawal_paused
                                              then custody_tenant_chain.withdrawal_paused_at else now() end,
                    withdrawal_paused_by=case when custody_tenant_chain.withdrawal_paused
                                              then custody_tenant_chain.withdrawal_paused_by else excluded.withdrawal_paused_by end,
                    updated_at=now()
                """,tenantId,chain,actorId,actorId);
    }

    /** Resume must serialize with an emergency stop before inspecting quarantine state. */
    public void lockEmergencyPauseCoordination(UUID tenantId,String chain) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("withdrawal resume requires one committed transaction");
        lockLegacyAdvisory(tenantId,chain,false);
    }

    private void lockLegacyAdvisory(UUID tenantId,String chain,boolean shared) {
        String sql=shared
                ?"select pg_advisory_xact_lock_shared(hashtextextended(?::text,0))"
                :"select pg_advisory_xact_lock(hashtextextended(?::text,0))";
        jdbc.query(sql,rs->{rs.next();return null;},legacyLockKey(tenantId,chain));
    }

    private static String legacyLockKey(UUID tenantId,String chain) {
        return tenantId+":"+chain.toUpperCase(java.util.Locale.ROOT);
    }

    public static final class ExternalBroadcastLease implements AutoCloseable {
        private final Connection connection;
        private final boolean autoCommit;
        private boolean closed;
        private ExternalBroadcastLease(Connection connection,boolean autoCommit) {
            this.connection=connection;this.autoCommit=autoCommit;
        }
        @Override public void close() {
            if(closed)return;
            closed=true;
            try {connection.rollback();connection.setAutoCommit(autoCommit);}
            catch(SQLException error){throw new IllegalStateException("native relay lock release failed",error);}
            finally {try{connection.close();}catch(SQLException error){throw new IllegalStateException("native relay connection close failed",error);}}
        }
    }

    public boolean lockForHotWalletActivation(UUID tenantId, String chain) {
        return Boolean.TRUE.equals(jdbc.query("""
                select status from custody_tenant_chain
                 where tenant_id=? and upper(chain)=upper(?) for update
                """, rs -> rs.next() && "ACTIVE".equals(rs.getString(1)),
                tenantId, chain));
    }

    public int setWithdrawalPaused(UUID tenantId, String chain, boolean paused, UUID actorId) {
        return jdbc.update("""
                update custody_tenant_chain
                   set withdrawal_paused = ?,
                       withdrawal_paused_at = case when ? then now() else null end,
                       withdrawal_paused_by = case when ? then ?::uuid else null end,
                       updated_at = now()
                 where tenant_id = ? and upper(chain) = upper(?)
                """, paused, paused, paused, actorId, tenantId, chain);
    }

    /** 查询租户当前已启用的链名称。 */
    public List<String> listActiveChains(UUID tenantId) {
        return jdbc.queryForList("""
                select chain
                  from custody_tenant_chain
                 where tenant_id = ? and status = 'ACTIVE'
                """, String.class, tenantId);
    }

    public List<TenantChain> listActiveByTenants(List<UUID> tenantIds) {
        if (tenantIds.isEmpty()) return List.of();
        return jdbc.query("""
                select tenant_id, chain from custody_tenant_chain where tenant_id=any(?) and status='ACTIVE'
                """, (rs, rowNum) -> new TenantChain(rs.getObject("tenant_id", UUID.class), rs.getString("chain")),
                new org.springframework.jdbc.support.SqlArrayValue("uuid", tenantIds.toArray()));
    }

    public record TenantChain(UUID tenantId, String chain) { }

    /** 更新租户链启用状态。 */
    public void setStatus(UUID tenantId, String chain, String status, UUID actorId) {
        jdbc.update("""
                insert into custody_tenant_chain(
                    tenant_id, chain, status, opened_by, opened_at,
                    closed_by, closed_at, updated_at)
                values (?, ?, ?,
                        case when ? = 'ACTIVE' then ?::uuid end,
                        case when ? = 'ACTIVE' then now() end,
                        case when ? = 'CLOSED' then ?::uuid end,
                        case when ? = 'CLOSED' then now() end,
                        now())
                on conflict (tenant_id, chain) do update set
                    status = excluded.status,
                    opened_by = case when excluded.status = 'ACTIVE' then excluded.opened_by
                                     else custody_tenant_chain.opened_by end,
                    opened_at = case when excluded.status = 'ACTIVE' then now()
                                     else custody_tenant_chain.opened_at end,
                    closed_by = case when excluded.status = 'CLOSED' then excluded.closed_by
                                     else null end,
                    closed_at = case when excluded.status = 'CLOSED' then now() else null end,
                    updated_at = now()
                """, tenantId, chain, status,
                status, actorId, status, status, actorId, status);
    }

    /** 转换可空时间字段。 */
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 租户链数据库记录。 */
    public record ChainRow(
            String chain,
            String status,
            boolean withdrawalPaused,
            Instant openedAt,
            Instant closedAt
    ) {
    }
}
