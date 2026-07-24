package com.surprising.wallet.job.collection;

import com.surprising.wallet.account.service.AccountChainWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * Account-Chain 提现处理任务，构建签名交易并推送到签名流水。
 */
public class AccountChainWithdrawalProcessJob {

    /** 提现与签名流程编排服务。 */
    private final AccountChainWorkflowService workflowService;

    /**
     * Account-Chain 提现处理任务。
     * <p>
     * 每 30 秒（offset 13s）执行一次：遍历所有启用的 account-chain 链，
     * 从 DB 拉取待签名的 withdrawal_order，构建签名交易并推送到
     * Redis 签名队列（sig:first → sig1 → sig2 → sig:done → 广播）。
     * <p>
     * EVM 链若启用了 EIP-7702，则跳过（由 {@code Evm7702WithdrawalJob} 单独处理）。
     * <p>
     * 注意：UTXO 链提现由 {@code *UtxoBatchJob} 处理。
     */
    @Scheduled(scheduler = "accountTaskScheduler", cron = "13/30 * * * * ?")
    public void run() {
        log.debug("AccountChain withdrawal process job begin");
        workflowService.processWithdrawals();
        log.debug("AccountChain withdrawal process job end");
    }
}
