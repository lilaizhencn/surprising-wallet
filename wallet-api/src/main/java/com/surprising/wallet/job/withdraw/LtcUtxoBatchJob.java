package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class LtcUtxoBatchJob extends UtxoBatchJob {
    @Override
    protected String chain() {
        return "LTC";
    }

    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "10/30 * * * * ?")
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        super.execute();
    }

    @Override
    protected int defaultFeeRate() {
        return (int) LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
    }
}
