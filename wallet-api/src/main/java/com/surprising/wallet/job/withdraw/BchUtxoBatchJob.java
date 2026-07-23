package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BchUtxoBatchJob extends UtxoBatchJob {
/**
 * BCH 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：从 DB 拉取 BCH 链所有待签名的 withdrawal_order，
 * 选取可用 UTXO 构建批量交易，推送到 Redis 一次签名队列。
 *
 * @author atomex
 */
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
