package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DogeUtxoBatchJob extends UtxoBatchJob {
/**
 * DOGE 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：从 DB 拉取 DOGE 链所有待签名的 withdrawal_order，
 * 选取可用 UTXO 构建批量交易（P2WSH 多签），推送到 Redis 一次签名队列。
 *
 * @author atomex
 */
    @Override
    protected String chain() {
        return "DOGE";
    }

    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "12/30 * * * * ?")
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
