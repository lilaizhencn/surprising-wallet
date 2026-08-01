package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.UtxoBatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** BCH 链提现与归集批处理调度任务。 */
@Component
public class BchUtxoBatchJob {
    /** UTXO 批处理业务服务。 */
    private final UtxoBatchService batchService;

    /** 构造 BCH 批处理调度器。 */
    public BchUtxoBatchJob(UtxoBatchService batchService) {
        this.batchService = batchService;
    }

    /** 每 30 秒触发一次 BCH 批处理。 */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "14/30 * * * * ?")
    public void execute() {
        batchService.execute("BCH");
    }
}
