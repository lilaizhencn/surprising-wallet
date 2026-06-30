package com.surprising.wallet.jobs.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers LTC withdrawal and collection transactions that were locked in the
 * database but lost from Redis because a process stopped mid-pipeline.
 */
@Slf4j
@Component
public class LtcSigningRecoveryJob {
    private final ChainJdbcRepository repository;
    private final BlockchainRuntimeService blockchainRuntimeService;
    private final WalletRuntimeConfigService runtimeConfigService;

    private static final long STALE_SECONDS = 60;

    public LtcSigningRecoveryJob(ChainJdbcRepository repository,
                                 BlockchainRuntimeService blockchainRuntimeService,
                                 WalletRuntimeConfigService runtimeConfigService) {
        this.repository = repository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @Scheduled(cron = "15/30 * * * * ?")
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata("LTC");
        for (WithdrawTransaction transaction : repository.findStaleBitcoinLikeSigningTransactions(
                currency, STALE_SECONDS)) {
            if (!repository.claimBitcoinLikeSigningRecovery(
                    currency, transaction.getId(), STALE_SECONDS)) {
                continue;
            }
            currency.applyTo(transaction);
            REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
            log.info("requeued stale LTC signing transaction id={}", transaction.getId());
        }
    }

    private boolean isEnabled() {
        return runtimeConfigService.isTaskEnabled("LTC", WalletRuntimeConfigService.TASK_WITHDRAW);
    }
}
