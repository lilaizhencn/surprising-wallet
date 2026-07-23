package com.surprising.wallet.custody.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.surprising.wallet.custody.service.CustodyAssetRecoveryService;

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
