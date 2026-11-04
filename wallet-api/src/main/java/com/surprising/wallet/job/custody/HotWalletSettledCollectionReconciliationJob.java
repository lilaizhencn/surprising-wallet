package com.surprising.wallet.job.custody;

import com.surprising.wallet.service.HotWalletSettledCollectionReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances direct EVM inbound capacity only after independently finalized payment and balance proof. */
@Component @RequiredArgsConstructor
public class HotWalletSettledCollectionReconciliationJob {
    private final HotWalletSettledCollectionReconciliationService reconciler;

    @Scheduled(scheduler="custodyTaskScheduler",
            fixedDelayString="${custody.hot-wallet.finalized-collection-delay-ms:30000}")
    public void reconcile() { reconciler.reconcilePending(); }
}
