package com.surprising.wallet.job.collection;

import com.surprising.wallet.service.AccountChainWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 负责定时任务入口和调度编排，实际业务处理委托给服务层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
/**
 * Account-Chain 提现确认任务，检查 sent 订单链上状态并落状态事件。
 */
public class AccountChainWithdrawalConfirmJob {

    /** 提现与链上确认编排服务。 */
    private final AccountChainWorkflowService workflowService;

    /**
     * Account-Chain 提现确认任务。
     * <p>
     * 每 30 秒（offset 15s）执行一次：遍历所有启用的 account-chain 链，
     * 查询状态为 SENT 的 withdrawal_order，通过链上 RPC 确认交易是否
     * 已打包上链，更新提现状态（CONFIRMED / FAILED）并写入 custody_event。
     */
    @Scheduled(scheduler = "accountTaskScheduler", cron = "15/30 * * * * ?")
    public void run() {
        log.debug("AccountChain withdrawal confirm job begin");
        workflowService.confirmWithdrawals();
        log.debug("AccountChain withdrawal confirm job end");
    }
}
