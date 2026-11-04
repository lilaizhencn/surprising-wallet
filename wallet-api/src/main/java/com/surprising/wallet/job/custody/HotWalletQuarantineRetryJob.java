package com.surprising.wallet.job.custody;

import com.surprising.wallet.service.CustodyHotWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays the original one-way event until the independent approval fence is confirmed. */
@Component @RequiredArgsConstructor
public class HotWalletQuarantineRetryJob {
    private final CustodyHotWalletService wallets;

    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelay = 5000)
    public void reconcile() { wallets.reconcilePendingQuarantines(); }
}
