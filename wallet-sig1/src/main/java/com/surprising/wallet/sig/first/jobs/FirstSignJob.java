package com.surprising.wallet.sig.first.jobs;

import com.surprising.wallet.sig.first.service.FirstSigningService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FirstSignJob {
    private final FirstSigningService service;
    @Scheduled(scheduler = "sig1TaskScheduler", fixedDelayString = "${sw.wallet.signing.delay:PT1S}",
            initialDelayString = "${sw.wallet.signing.initial-delay:PT10S}")
    public void execute() { service.process(); }
}
