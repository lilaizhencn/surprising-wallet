package com.surprising.wallet.job.collection;

import com.surprising.wallet.account.service.AccountChainWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountChainProcessCollectionsJob {

    private final AccountChainWorkflowService workflowService;

    @Scheduled(scheduler = "accountTaskScheduler", cron = "17/30 * * * * ?")
    public void run() {
        log.debug("AccountChain process collections job begin");
        workflowService.processCollections();
        workflowService.confirmCollections();
        log.debug("AccountChain process collections job end");
    }
}
