package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BatchDogeWithdrawJob extends AbstractBatchWithdrawJob {
    @Override
    protected String chain() {
        return "DOGE";
    }

    @Scheduled(cron = "12/30 * * * * ?")
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        super.execute();
    }

    @Override
    protected int defaultFeeRate() {
        return (int) DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE;
    }

    @Override
    protected long estimateNetworkFeeAtomic(int inputCount, int outputCount, int feeRate) {
        long bytes = P2shMultisigFeeCalculator.estimateBytes(inputCount, outputCount, 2, 3);
        return DogecoinFeePolicy.feeForBytes(bytes, feeRate);
    }
}
