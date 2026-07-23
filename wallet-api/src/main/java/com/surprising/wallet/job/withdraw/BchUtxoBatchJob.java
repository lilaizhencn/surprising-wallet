package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BchUtxoBatchJob extends UtxoBatchJob {
    @Override
    protected String chain() {
        return "BCH";
    }

    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "14/30 * * * * ?")
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        super.execute();
    }

    @Override
    protected int defaultFeeRate() {
        return (int) BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;
    }

    @Override
    protected long estimateNetworkFeeAtomic(int inputs, int outputs, int feeRate) {
        return P2shMultisigFeeCalculator.estimateBytes(inputs, outputs, 2, 3) * feeRate;
    }
}
