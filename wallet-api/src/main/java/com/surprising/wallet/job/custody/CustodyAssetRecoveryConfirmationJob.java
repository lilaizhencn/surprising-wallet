package com.surprising.wallet.job.custody;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.surprising.wallet.custody.service.CustodyAssetRecoveryService;

@Component
public class CustodyAssetRecoveryConfirmationJob {
    private final CustodyAssetRecoveryService recoveries;

    public CustodyAssetRecoveryConfirmationJob(CustodyAssetRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelayString = "${custody.asset-recovery.confirm-delay-ms:15000}")
    public void confirmBroadcastRecoveries() {
        recoveries.confirmBroadcastRecoveries();
    }
}
