package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.WithdrawalQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BroadCastSignedTxJob {
    private final WithdrawalQueueService service;
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 1000)
    public void run() { service.broadcast(); }
}
