package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.FeeRateUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeeRateUpdater {
    private final FeeRateUpdateService service;
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0 */2 * * * ?")
    public void updateFeeRate() { service.updateFeeRate(); }
}
