package com.surprising.wallet.withdraw.job;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requeues stale DOGE signing rows that were lost from Redis.
 */
@Component
public class DogeSigningRecoveryJob {
    private final ChainJdbcRepository repository;
    private final BlockchainRuntimeService blockchainRuntimeService;
    private final WalletRuntimeConfigService runtimeConfigService;

    private static final long STALE_SECONDS = 60;

    public DogeSigningRecoveryJob(ChainJdbcRepository repository,
                                  BlockchainRuntimeService blockchainRuntimeService,
                                  WalletRuntimeConfigService runtimeConfigService) {
        this.repository = repository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @Scheduled(cron = "17/30 * * * * ?")
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata("DOGE");
        for (WithdrawTransaction transaction : repository.findStaleBitcoinLikeSigningTransactions(
                currency, STALE_SECONDS)) {
            if (!repository.claimBitcoinLikeSigningRecovery(
                    currency, transaction.getId(), STALE_SECONDS)) {
                continue;
            }
            currency.applyTo(transaction);
            REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
        }
    }

    private boolean isEnabled() {
        return runtimeConfigService.isTaskEnabled("DOGE", WalletRuntimeConfigService.TASK_WITHDRAW);
    }
}
