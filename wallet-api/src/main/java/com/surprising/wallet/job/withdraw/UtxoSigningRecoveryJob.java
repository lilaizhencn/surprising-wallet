package com.surprising.wallet.job.withdraw;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.surprising.wallet.service.UtxoSigningRecoveryService;

/**
 * UTXO 链签名恢复任务。
 * <p>
 * 每 30 秒执行一次：依次检查 BTC/BCH/LTC/DOGE 四条链，扫描 DB 中超过
 * 60 秒仍处于 SIGNING 状态的 chain_signing_transaction 记录，
 * 将它们重新推送到 Redis 一次签名队列（sig:first）。
 * <p>
 * 恢复场景：sig1/sig2 进程在中途崩溃或重启，导致已从 DB 取出但尚未
 * 完成签名的交易卡在中间态——本任务确保这些交易被重新拾取处理。
 * <p>
 * 历史遗漏：此前只有 BCH/LTC/DOGE 有独立的 recovery job，
 * BTC 被忽视了。本类合并后统一覆盖四条链。
 *
 * @author atomex
 */
@Component
public class UtxoSigningRecoveryJob {
    /** 签名恢复业务服务。 */
    private final UtxoSigningRecoveryService recoveryService;

    /** 构造签名恢复调度器。 */
    public UtxoSigningRecoveryJob(UtxoSigningRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /**
     * 每 30 秒扫描一次待签名悬挂交易，回推至首次签名队列，避免重启后遗留交易丢失。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 30_000)
    public void execute() {
        recoveryService.recover();
    }
}
