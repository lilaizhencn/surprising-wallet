package com.surprising.wallet.job.collection;

import com.surprising.wallet.account.service.AccountChainWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountChainMoneroWorkflowJob {

    private final AccountChainWorkflowService workflowService;

    @Scheduled(scheduler = "accountTaskScheduler", cron = "3/10 * * * * ?")
    public void run() {
        log.debug("AccountChain Monero workflow job begin");
        workflowService.moneroWorkflow();
        log.debug("AccountChain Monero workflow job end");
    }
}
