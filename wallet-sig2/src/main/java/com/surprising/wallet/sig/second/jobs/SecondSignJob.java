package com.surprising.wallet.sig.second.jobs;

import com.surprising.wallet.sig.second.service.SecondSigningService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecondSignJob {
    private final SecondSigningService service;
    @Scheduled(fixedDelayString = "${sw.wallet.signing.delay:PT1S}",
            initialDelayString = "${sw.wallet.signing.initial-delay:PT10S}")
    public void execute() { service.process(); }
}
