package com.surprising.wallet.job.devfaucet;

import com.surprising.wallet.service.DevFaucetService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 开发环境水龙头调度任务，只负责定时触发水龙头业务服务。
 */
@Component
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
public class DevFaucetJob {
    /** 水龙头业务服务。 */
    private final DevFaucetService faucetService;

    /** 构造水龙头调度器。 */
    public DevFaucetJob(DevFaucetService faucetService) {
        this.faucetService = faucetService;
    }

    /** 定时触发一轮补币流程，业务异常由调度边界隔离。 */
    @Scheduled(
            fixedDelayString = "${sw.wallet.dev-faucet.delay:PT10S}",
            initialDelayString = "${sw.wallet.dev-faucet.delay:PT10S}")
    public void execute() {
        try {
            faucetService.runOnce();
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(DevFaucetJob.class)
                    .error("dev faucet scheduled pass failed", error);
        }
    }
}
