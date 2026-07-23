package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class LtcUtxoBatchJob extends UtxoBatchJob {
/**
 * LTC 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：从 DB 拉取 LTC 链所有待签名的 withdrawal_order，
 * 选取可用 UTXO 构建批量交易（P2WSH 多签），推送到 Redis 一次签名队列。
 *
 * @author atomex
 */
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
