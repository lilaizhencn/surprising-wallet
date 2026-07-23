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

/**
 * 第一次签名服务（sig1）。
 * <p>
 * 每 10 秒执行一次：从 Redis "一次签名队列"（sig:first）拉取待签名的提现/归集交易，
 * 调用对应链的签名算法（ECDSA / EdDSA / Schnorr）生成首次签名，
 * 完成后推送到 Redis "二次签名队列"（sig:second），签名失败则直接推送到完成队列。
 * <p>
 * sig1 + sig2 双重签名是为了满足多签安全模型：sig1 持有部分密钥分片，
 * sig2 持有另一部分，只有两方都签名交易才有效。
 *
 * @author atomex
 */
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
