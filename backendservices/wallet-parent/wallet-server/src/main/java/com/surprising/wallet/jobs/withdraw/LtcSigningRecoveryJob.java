package com.surprising.wallet.jobs.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Recovers LTC withdrawal and collection transactions that were locked in the
 * database but lost from Redis because a process stopped mid-pipeline.
 */
@Slf4j
@Component
public class LtcSigningRecoveryJob {
    private final ChainJdbcRepository repository;

    @Value("${atomex.wallet.recovery.enabled-currencies:}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.recovery.signing-stale-seconds:60}")
    private long staleSeconds;

    public LtcSigningRecoveryJob(ChainJdbcRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "15/30 * * * * ?")
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        for (WithdrawTransaction transaction : repository.findStaleLitecoinSigningTransactions(staleSeconds)) {
            if (!repository.claimLitecoinSigningRecovery(transaction.getId(), staleSeconds)) {
                continue;
            }
            REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, JSONObject.toJSONString(transaction));
            log.info("requeued stale LTC signing transaction id={}", transaction.getId());
        }
    }

    private boolean isEnabled() {
        return Arrays.stream(enabledCurrencies.split(","))
                .map(String::trim)
                .anyMatch(item -> "*".equals(item) || "ltc".equalsIgnoreCase(item));
    }
}
