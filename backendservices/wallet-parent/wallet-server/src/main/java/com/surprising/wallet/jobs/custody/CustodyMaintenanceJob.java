package com.surprising.wallet.jobs.custody;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CustodyMaintenanceJob {
    private final CustodyRepository repository;

    public CustodyMaintenanceJob(CustodyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${sw.wallet.custody.security-cleanup-cron:0 17 3 * * *}")
    public void cleanupExpiredSecurityRows() {
        int deleted = repository.cleanupExpiredSecurityRows();
        if (deleted > 0) {
            log.info("Deleted {} expired custody security rows", deleted);
        }
    }
}
