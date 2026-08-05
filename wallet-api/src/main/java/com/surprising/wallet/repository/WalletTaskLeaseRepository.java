package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** wallet_task_lease 单表仓储。 */
@Repository
public class WalletTaskLeaseRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造任务租约仓储。 */
    public WalletTaskLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 原子领取任务租约，只有租约已过期或仍由当前工作者持有时才允许更新。 */
    public boolean acquire(String taskName, String ownerId, Duration leaseDuration) {
        Instant leaseUntil = Instant.now().plus(leaseDuration);
        return jdbc.update("""
                insert into wallet_task_lease(task_name, owner_id, lease_until, heartbeat_at, updated_at)
                values (?, ?, ?, now(), now())
                on conflict (task_name) do update set
                    owner_id = excluded.owner_id,
                    lease_until = excluded.lease_until,
                    heartbeat_at = now(),
                    updated_at = now()
                 where wallet_task_lease.lease_until <= now()
                    or wallet_task_lease.owner_id = excluded.owner_id
                """, taskName, ownerId, Timestamp.from(leaseUntil)) == 1;
    }

    /** 延长当前工作者仍持有的租约。 */
    public boolean renew(String taskName, String ownerId, Duration leaseDuration) {
        return jdbc.update("""
                update wallet_task_lease
                   set lease_until = ?, heartbeat_at = now(), updated_at = now()
                 where task_name = ? and owner_id = ? and lease_until > now()
                """, Timestamp.from(Instant.now().plus(leaseDuration)), taskName, ownerId) == 1;
    }

    /** 释放当前工作者持有的任务租约。 */
    public void release(String taskName, String ownerId) {
        jdbc.update("""
                update wallet_task_lease
                   set lease_until = now(), heartbeat_at = now(), updated_at = now()
                 where task_name = ? and owner_id = ?
                """, taskName, ownerId);
    }
}
