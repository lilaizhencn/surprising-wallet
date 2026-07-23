package com.surprising.wallet.sig.second.jobs;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.second.ISignService;
import com.surprising.wallet.sig.second.SignContent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;

@Component
@Slf4j
@RequiredArgsConstructor
public class SecondSignJob {

    private static final Duration DELAY = Duration.ofSeconds(10);
    private static final HexFormat HEX = HexFormat.of();

    private final TaskScheduler taskScheduler;

    @PostConstruct
    void schedule() {
        taskScheduler.scheduleWithFixedDelay(this::execute, DELAY);
        log.info("SecondSignJob scheduled with fixed delay {}ms", DELAY.toMillis());
    }

    void execute() {
        String key = Constants.WALLET_WITHDRAW_SIG_SECOND_KEY;
        String tmp = Constants.WALLET_WITHDRAW_SIG_SECOND_TMP_KEY;

        try {
            if (ObjectUtils.isEmpty(Constants.NET_PARAMS)) {
                log.info("第二次签名服务校验钱包环境 没有初始化");
                return;
            }
            String txStr = REDIS.rPoplPush(key, tmp);
            if (ObjectUtils.isEmpty(txStr)) {
                return;
            }

            WithdrawTransaction transaction = JSONObject.parseObject(txStr, WithdrawTransaction.class);
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = SignContent.getSignService(currency);
            JSONObject signature = JSONObject.parseObject(transaction.getSignature());
            if (signService == null) {
                signature.put("valid", false);
                signature.put("error", "no sign service for " + currency.getName());
            } else {
                String rawTransaction = signService.signTransaction(transaction);
                signature = JSONObject.parseObject(transaction.getSignature());
                if (StringUtils.hasText(rawTransaction)) {
                    Transaction signedTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(rawTransaction)));
                    signature.put("rawTransaction", rawTransaction);
                    signature.put("txId", signedTx.getTxId().toString());
                    signature.put("weight", signedTx.getWeight());
                    signature.put("vBytes", signedTx.getVsize());
                    signature.remove("firstSignTx");
                    signature.put("valid", true);
                    log.info("二次签名成功 txId={}, finalTxId={}, weight={}, vBytes={}",
                            transaction.getId(), signedTx.getTxId(), signedTx.getWeight(), signedTx.getVsize());
                } else {
                    signature.put("valid", false);
                    signature.putIfAbsent("error", "second sign returned empty raw transaction");
                    log.warn("二次签名失败 txId={}, error={}", transaction.getId(), signature.getString("error"));
                }
            }
            transaction.setSignature(signature.toJSONString());
            REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_DONE_KEY, JSONObject.toJSONString(transaction));
            REDIS.lPop(tmp);

        } catch (DataAccessException e) {
            log.info("Signature second job redis error", e);
        } catch (Throwable e) {
            log.error("Signature second job error, will retry", e);
        }
    }
}
