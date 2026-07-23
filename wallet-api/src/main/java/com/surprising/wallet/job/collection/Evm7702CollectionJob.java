package com.surprising.wallet.job.collection;

import com.surprising.wallet.account.service.Evm7702CollectionWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Evm7702CollectionJob {

    private final Evm7702CollectionWorkflowService workflowService;

    @Scheduled(scheduler = "evm7702TaskScheduler", fixedDelayString = "${sw.wallet.evm7702.collection-delay:5000}")
    public void run() {
        log.debug("EVM 7702 collection job begin");
        workflowService.run();
        log.debug("EVM 7702 collection job end");
    }
}
