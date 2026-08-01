package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.service.UtxoBatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** BTC 链提现与归集批处理调度任务。 */
@Component
public class BtcUtxoBatchJob {
    /** UTXO 批处理业务服务。 */
    private final UtxoBatchService batchService;

    /** 构造 BTC 批处理调度器。 */
    public BtcUtxoBatchJob(UtxoBatchService batchService) {
        this.batchService = batchService;
    }

    /** 每 30 秒触发一次 BTC 批处理。 */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0/30 * * * * ?")
    public void execute() {
        batchService.execute("BTC");
    }
}
