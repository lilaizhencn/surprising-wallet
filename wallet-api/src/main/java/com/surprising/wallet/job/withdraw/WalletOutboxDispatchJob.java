package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.WalletOutboxDispatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 钱包签名 Outbox 派发任务。 */
@Component
public class WalletOutboxDispatchJob {
    /** Outbox 派发服务。 */
    private final WalletOutboxDispatchService dispatchService;

    /** 构造 Outbox 派发任务。 */
    public WalletOutboxDispatchJob(WalletOutboxDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 每秒派发一次首次签名任务。 */
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 1000)
    public void dispatchFirstSigning() {
        dispatchService.dispatch(WalletOutboxDispatchService.SIGNING_FIRST_TOPIC,
                Constants.WALLET_WITHDRAW_SIG_FIRST_KEY);
    }

    /** 每秒派发一次二次签名任务。 */
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 1000)
    public void dispatchSecondSigning() {
        dispatchService.dispatch(WalletOutboxDispatchService.SIGNING_SECOND_TOPIC,
                Constants.WALLET_WITHDRAW_SIG_SECOND_KEY);
    }
}
