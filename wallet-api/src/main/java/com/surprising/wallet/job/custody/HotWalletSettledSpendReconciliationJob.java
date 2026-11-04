package com.surprising.wallet.job.custody;

import com.surprising.wallet.service.HotWalletSettledSpendReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances only independently finalized, exact direct EVM spends. */
@Component @RequiredArgsConstructor
public class HotWalletSettledSpendReconciliationJob {
    private final HotWalletSettledSpendReconciliationService reconciler;

    @Scheduled(scheduler="custodyTaskScheduler",
            fixedDelayString="${custody.hot-wallet.finalized-spend-delay-ms:30000}")
    public void reconcile() { reconciler.reconcilePending(); }
}
