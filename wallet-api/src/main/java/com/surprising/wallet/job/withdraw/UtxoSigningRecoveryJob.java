package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
@Slf4j
@Component
public class UtxoSigningRecoveryJob {

    /** 重试阈值（秒）：超过该时长仍在 SIGNING 状态则认为卡住。 */
    private static final long STALE_SECONDS = 60;
    /** 统一扫描链列表。 */
    private static final List<String> CHAINS = List.of("BTC", "BCH", "LTC", "DOGE");

    /** 签名交易仓储。 */
    @Autowired
    private ChainJdbcRepository repository;
    /** 链元数据服务。 */
    @Autowired
    private BlockchainRuntimeService blockchainRuntimeService;
    /** 任务开关服务。 */
    @Autowired
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 每 30 秒扫描一次待签名悬挂交易，回推至首次签名队列，避免重启后遗留交易丢失。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 30_000)
    public void execute() {
        for (String chain : CHAINS) {
            try {
                if (!runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_WITHDRAW)) {
                    continue;
                }
                AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata(chain);
                for (WithdrawTransaction tx : repository.findStaleBitcoinLikeSigningTransactions(
                        currency, STALE_SECONDS)) {
                    if (!repository.claimBitcoinLikeSigningRecovery(
                            currency, tx.getId(), STALE_SECONDS)) {
                        continue;
                    }
                    currency.applyTo(tx);
                    REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(tx));
                    log.info("requeued stale {} signing transaction id={}", chain, tx.getId());
                }
            } catch (Throwable e) {
                log.error("UTXO signing recovery failed for chain {}: {}", chain, e.getMessage(), e);
            }
        }
    }
}
