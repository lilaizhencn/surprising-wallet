package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.UtxoBatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** LTC 链提现与归集批处理调度任务。 */
@Component
public class LtcUtxoBatchJob {
    /** UTXO 批处理业务服务。 */
    private final UtxoBatchService batchService;

    /** 构造 LTC 批处理调度器。 */
    public LtcUtxoBatchJob(UtxoBatchService batchService) {
        this.batchService = batchService;
    }

    /** 每 30 秒触发一次 LTC 批处理。 */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "10/30 * * * * ?")
    public void execute() {
        batchService.execute("LTC");
    }
}
