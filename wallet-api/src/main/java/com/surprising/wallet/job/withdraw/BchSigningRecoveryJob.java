package com.surprising.wallet.job.withdraw;
import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class BchSigningRecoveryJob{
    private final ChainJdbcRepository repo;
    private final BlockchainRuntimeService blockchainRuntimeService;
    private final WalletRuntimeConfigService runtimeConfigService;
    private static final long STALE_SECONDS=60;
    public BchSigningRecoveryJob(ChainJdbcRepository repo,BlockchainRuntimeService blockchainRuntimeService,WalletRuntimeConfigService runtimeConfigService){this.repo=repo;this.blockchainRuntimeService=blockchainRuntimeService;this.runtimeConfigService=runtimeConfigService;}
    @Scheduled(cron="19/30 * * * * ?") public void execute(){
        if(!runtimeConfigService.isTaskEnabled("BCH",WalletRuntimeConfigService.TASK_WITHDRAW))return;
        AssetRuntimeMetadata currency=blockchainRuntimeService.assetMetadata("BCH");
        for(var tx:repo.findStaleBitcoinLikeSigningTransactions(currency,STALE_SECONDS))
            if(repo.claimBitcoinLikeSigningRecovery(currency,tx.getId(),STALE_SECONDS)){
                currency.applyTo(tx);
                REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY,JSONObject.toJSONString(tx));
            }
    }
}
