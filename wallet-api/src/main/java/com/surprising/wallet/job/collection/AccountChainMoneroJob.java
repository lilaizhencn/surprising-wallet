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
 * Monero 全流程定时任务（扫描 -> 提现 -> 确认 -> 归集 -> 确认）。
 */
public class AccountChainMoneroJob {

    /** Monero 相关工作流服务。 */
    private final AccountChainWorkflowService workflowService;

    /**
     * Monero（XMR）全流程串行任务。
     * <p>
     * 每 10 秒（offset 3s）执行一次：Monero 是隐私链，其充值扫描依赖
     * wallet-rpc 导出 outputs，不能与提现/归集并行——因此 XMR 的
     * 扫充值 → 处理提现 → 确认提现 → 处理归集 → 确认归集 五个步骤
     * 在单链单次调用中串行完成，区别于其他链各步骤由独立 Job 并行调度。
     */
    @Scheduled(scheduler = "accountTaskScheduler", cron = "3/10 * * * * ?")
    public void run() {
        log.debug("AccountChain Monero job begin");
        workflowService.moneroWorkflow();
        log.debug("AccountChain Monero job end");
    }
}
