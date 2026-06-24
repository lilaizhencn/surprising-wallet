package com.surprising.wallet.jobs.withdraw;
import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Arrays;
@Component public class BchSigningRecoveryJob{
    private final ChainJdbcRepository repo;
    @Value("${atomex.wallet.recovery.enabled-currencies:}") String enabled;
    @Value("${atomex.wallet.recovery.signing-stale-seconds:60}") long stale;
    public BchSigningRecoveryJob(ChainJdbcRepository repo){this.repo=repo;}
    @Scheduled(cron="19/30 * * * * ?") public void execute(){
        if(Arrays.stream(enabled.split(",")).map(String::trim).noneMatch(v->"*".equals(v)||"bch".equalsIgnoreCase(v)))return;
        for(var tx:repo.findStaleBitcoinLikeSigningTransactions(RuntimeAsset.BCH,stale))
            if(repo.claimBitcoinLikeSigningRecovery(RuntimeAsset.BCH,tx.getId(),stale))
                REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY,JSONObject.toJSONString(tx));
    }
}
