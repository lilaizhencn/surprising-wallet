package com.surprising.wallet.jobs.withdraw;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component public class BatchBchWithdrawJob extends AbstractBatchWithdrawJob{
    @PostConstruct public void init(){currency=blockchainRuntimeService.assetMetadata("BCH");}
    @Scheduled(cron="14/30 * * * * ?") @Override @Transactional(rollbackFor=Throwable.class) public void execute(){super.execute();}
    @Override protected int defaultFeeRate(){return (int)BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;}
    @Override protected long estimateNetworkFeeAtomic(int inputs,int outputs,int feeRate){
        return P2shMultisigFeeCalculator.estimateBytes(inputs,outputs,2,3)*feeRate;
    }
}
