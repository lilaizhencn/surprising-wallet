package com.surprising.wallet.job.custody;

import com.surprising.wallet.service.HotWalletInventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes active hot-wallet evidence without holding a database transaction over RPC. */
@Component @RequiredArgsConstructor
public class HotWalletInventoryRefreshJob {
    private final HotWalletInventoryService inventory;

    @Scheduled(scheduler="custodyTaskScheduler",
            fixedDelayString="${custody.hot-wallet.inventory-refresh-delay-ms:5000}")
    public void refresh() { inventory.refreshActive(); }
}
