package com.surprising.wallet.sig.first.jobs;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.sig.first.SignContent;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.first.service.ISignService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
public class FirstSignJob {

    private static final Duration DELAY = Duration.ofSeconds(10);

    private final TaskScheduler taskScheduler;

    @Autowired
    SignContent signContent;

    @PostConstruct
    void schedule() {
        taskScheduler.scheduleWithFixedDelay(this::execute, DELAY);
        log.info("FirstSignJob scheduled with fixed delay {}ms", DELAY.toMillis());
    }

    void execute() {
        String key = Constants.WALLET_WITHDRAW_SIG_FIRST_KEY;
        String tmp = Constants.WALLET_WITHDRAW_SIG_FIRST_TMP_KEY;

        try {
            if (ObjectUtils.isEmpty(Constants.NET_PARAMS)) {
                log.info("第一次签名服务校验钱包环境 没有初始化");
                return;
            }
            String txStr = REDIS.rPoplPush(key, tmp);
            if (ObjectUtils.isEmpty(txStr)) {
                return;
            }

            log.info("获取到的第一次交易的数据:{}", txStr);
            JSONObject txJson = JSONObject.parseObject(txStr);
            WithdrawTransaction transaction = txJson.toJavaObject(WithdrawTransaction.class);
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = signContent.getSignService(currency);
            if (signService == null) {
                JSONObject signature = JSONObject.parseObject(transaction.getSignature());
                signature.put("valid", false);
                signature.put("error", "no first sign service for " + currency.getName());
                transaction.setSignature(signature.toJSONString());
            } else {
                signService.signTransaction(transaction);
            }
            String signatureStr = transaction.getSignature();
            JSONObject sigJson = JSONObject.parseObject(signatureStr);
            String rKey;
            if (sigJson.getBoolean("valid")) {
                log.info("签名验证成功 开始推送到第二次签名服务队列");
                rKey = Constants.WALLET_WITHDRAW_SIG_SECOND_KEY;
            } else {
                log.warn("签名验证失败 推送到签名失败队列");
                rKey = Constants.WALLET_WITHDRAW_SIG_DONE_KEY;
            }
            REDIS.lPush(rKey, JSONObject.toJSONString(transaction));
            REDIS.lPop(tmp);
            log.info("签名验证推送完成 key:{}", rKey);

        } catch (DataAccessException e) {
            log.info("Signature first job error", e);
        } catch (Throwable e) {
            log.error("Signature first job error, will retry", e);
        }
    }
}
