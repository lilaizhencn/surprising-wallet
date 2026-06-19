package com.surprising.wallet.sig.first.jobs;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.sig.first.SignContent;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.annotation.StartThread;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.first.service.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

/**
 * @author lilaizhen
 */
@Component
@StartThread
@Slf4j
public class FirstSignJob implements Runnable {

    private final long WAIT_TIME = 10 * 1000L;
    @Autowired
    SignContent signContent;

    @Override
    public void run() {
        log.info("Signature first job begin");
        String key = Constants.WALLET_WITHDRAW_SIG_FIRST_KEY;
        String tmp = Constants.WALLET_WITHDRAW_SIG_FIRST_TMP_KEY;
        while (true) {
            try {
                if (ObjectUtils.isEmpty(Constants.NET_PARAMS)) {
                    log.info("第一次签名服务校验钱包环境 没有初始化");
                    Thread.sleep(WAIT_TIME);
                    continue;
                }
                String txStr = REDIS.rPoplPush(key, tmp);
                if (!ObjectUtils.isEmpty(txStr)) {

                    log.info("获取到的第一次交易的数据:{}", txStr);
                    JSONObject txJson = JSONObject.parseObject(txStr);
                    WithdrawTransaction transaction = txJson.toJavaObject(WithdrawTransaction.class);
                    CurrencyEnum currency = CurrencyEnum.parseValue(transaction.getCurrency());
                    ISignService signService = signContent.getSignService(currency);
                    signService.signTransaction(transaction);
                    String signatureStr = transaction.getSignature();
                    JSONObject sigJson = JSONObject.parseObject(signatureStr);
                    String rKey;
                    if (sigJson.getBoolean("valid")) {
                        log.info("签名验证成功 开始推送到第二次签名服务队列");
                        rKey = Constants.WALLET_WITHDRAW_SIG_SECOND_KEY;
                    } else {
                        log.warn("签名验证失败 推送到签名失败队列");
                        //如果签名失败，直接推送到已完成队列，不需要再进行第二次签名
                        rKey = Constants.WALLET_WITHDRAW_SIG_DONE_KEY;
                    }
                    REDIS.lPush(rKey, JSONObject.toJSONString(transaction));
                    REDIS.lPop(tmp);
                    log.info("签名验证推送完成 key:{}", rKey);
                    continue;
                }

            } catch (DataAccessException e) {
                log.info("Signature first job error", e);

            } catch (Throwable e) {
                log.error("Signature first job quit", e);
                break;
            }

            try {
                Thread.sleep(WAIT_TIME);
            } catch (InterruptedException e) {
                log.info("Signature first job quit", e);
                break;
            }

        }

        log.info("Signature first job end");
    }
}
