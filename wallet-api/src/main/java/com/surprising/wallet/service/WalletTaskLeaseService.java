package com.surprising.wallet.service;

import com.surprising.wallet.repository.WalletTaskLeaseRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 钱包定时任务的数据库租约服务。 */
@Slf4j
@Service
public class WalletTaskLeaseService {
    /** 默认租约时长。 */
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    /** 当前进程唯一工作者标识。 */
    private final String ownerId = buildOwnerId();
    /** 任务租约仓储。 */
    private final WalletTaskLeaseRepository repository;
    /** 租约心跳线程。 */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "wallet-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /** 构造任务租约服务。 */
    public WalletTaskLeaseService(WalletTaskLeaseRepository repository) {
        this.repository = repository;
    }

    /** 尝试领取任务租约并执行任务，租约期间由心跳持续续租。 */
    public Object execute(String taskName, LeaseAction action) throws Throwable {
        try {
            if (!repository.acquire(taskName, ownerId, LEASE_DURATION)) {
                return null;
            }
        } catch (RuntimeException error) {
            log.warn("wallet scheduled task lease unavailable: task={} error={}", taskName, error.getMessage());
            return null;
        }

        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                repository.renew(taskName, ownerId, LEASE_DURATION);
            } catch (RuntimeException error) {
                log.warn("wallet scheduled task lease heartbeat failed: task={} error={}",
                        taskName, error.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            return action.run();
        } finally {
            heartbeat.cancel(false);
            try {
                repository.release(taskName, ownerId);
            } catch (RuntimeException error) {
                log.warn("wallet scheduled task lease release failed: task={} error={}",
                        taskName, error.getMessage());
            }
        }
    }

    /** 返回当前进程工作者标识。 */
    public String ownerId() {
        return ownerId;
    }

    /** 关闭租约心跳线程。 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /** 任务执行动作。 */
    @FunctionalInterface
    public interface LeaseAction {
        /** 执行任务。 */
        Object run() throws Throwable;
    }

    /** 创建包含 JVM 标识和随机值的工作者标识。 */
    private static String buildOwnerId() {
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        return "wallet-api-" + runtime + "-" + UUID.randomUUID();
    }
}
