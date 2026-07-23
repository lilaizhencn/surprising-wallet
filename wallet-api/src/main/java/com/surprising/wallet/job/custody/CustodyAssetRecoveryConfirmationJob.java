package com.surprising.wallet.job.custody;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.surprising.wallet.custody.service.CustodyAssetRecoveryService;

/**
 * 托管资产找回确认任务。
 * <p>
 * 每 15 秒执行一次：扫描已广播但未确认的资产找回交易
 * （custody_asset_recovery），通过链上 RPC 确认交易是否被打包，
 * 更新找回记录状态（RECOVERED / FAILED）。
 *
 * @author atomex
 */
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
