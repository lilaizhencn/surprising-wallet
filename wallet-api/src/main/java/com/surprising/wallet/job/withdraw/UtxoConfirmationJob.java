package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.BitcoinLikeConfirmationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UtxoConfirmationJob {
    private final BitcoinLikeConfirmationService service;

    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelayString = "${sw.wallet.utxo.confirm-delay:5000}")
    public void run() { service.confirmAll(); }
}
