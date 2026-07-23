package com.surprising.wallet.job.custody;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.surprising.wallet.custody.repository.CustodyRepository;

/**
 * 托管系统安全维护任务。
 * <p>
 * 每天凌晨 3:17 执行一次：清理过期的 session、idempotency key、
 * API nonce 等安全相关数据，防止表膨胀。
 *
 * @author atomex
 */
@Slf4j
@Component
public class CustodyMaintenanceJob {
    private final CustodyRepository repository;

    public CustodyMaintenanceJob(CustodyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(scheduler = "custodyTaskScheduler", cron = "${sw.wallet.custody.security-cleanup-cron:0 17 3 * * *}")
    public void cleanupExpiredSecurityRows() {
        int deleted = repository.cleanupExpiredSecurityRows();
        if (deleted > 0) {
            log.info("Deleted {} expired custody security rows", deleted);
        }
    }
}
