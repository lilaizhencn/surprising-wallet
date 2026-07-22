package com.surprising.wallet.jobs.custody;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustodyAssetRecoveryConfirmationJob {
    private final CustodyAssetRecoveryService recoveries;

    public CustodyAssetRecoveryConfirmationJob(CustodyAssetRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    @Scheduled(fixedDelayString = "${custody.asset-recovery.confirm-delay-ms:15000}")
    public void confirmBroadcastRecoveries() {
        recoveries.confirmBroadcastRecoveries();
    }
}
