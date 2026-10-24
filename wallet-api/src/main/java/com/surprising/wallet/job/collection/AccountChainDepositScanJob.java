package com.surprising.wallet.job.collection;

import com.surprising.wallet.service.AccountChainDepositWorkflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按扫描到期状态触发账户链充值扫描；UTXO 和 Monero 使用各自任务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountChainDepositScanJob {
    private final AccountChainDepositWorkflow deposits;

    @Scheduled(
            scheduler = "accountTaskScheduler",
            fixedDelayString = "${sw.wallet.account.deposit-schedule-check-delay:1000}")
    public void run() {
        log.debug("AccountChain deposit scan job begin");
        deposits.scanDueDeposits();
        log.debug("AccountChain deposit scan job end");
    }
}
