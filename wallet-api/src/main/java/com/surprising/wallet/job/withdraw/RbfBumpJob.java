package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.RbfBumpService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules BTC withdrawal/collection replacement requests from the tenant-owned RBF queue.
 * Requests contain transactionId and expectedTxId (the exact broadcast hash to replace).
 * Duplicate requests are acknowledged without another fee increase. The service retains
 * locked inputs and all broadcast attempts until one attempt reaches final confirmation.
 */
@Component
public class RbfBumpJob {

    /** RBF 业务服务。 */
    private final RbfBumpService bumpService;

    /** 构造 RBF 调度器。 */
    public RbfBumpJob(RbfBumpService bumpService) {
        this.bumpService = bumpService;
    }

    /**
     * 每 30 秒检查一次 RBF 触发队列，发现请求即执行重报流程。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0/30 * * * * ?")
    public void execute() {
        bumpService.process();
    }
}
