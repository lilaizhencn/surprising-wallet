package com.surprising.wallet.jobs.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
@Slf4j
public class RetryFailedWithdraw {

    @Autowired
    private TransactionService txService;

    @Scheduled(cron = "1 1/1 * * * ?")
    public void execute() {

        String failKey = Constants.WALLET_WITHDRAW_FAIL_KEY;
        String failKeyTmp = Constants.WALLET_WITHDRAW_FAIL_KEY_TMP;

        try {
            Long len = REDIS.lLen(failKey);
            while (len > 0) {
                log.info("重新尝试失败的交易 开始");
                String str = REDIS.rPoplPush(failKey, failKeyTmp);
                boolean success = false;
                try {
                    JSONObject json = JSONObject.parseObject(str);
                    WithdrawRecord record = json.toJavaObject(WithdrawRecord.class);
                    success = txService.withdraw(record);
                } catch (Throwable e) {
                    log.error("重新尝试失败的交易 异常 记录:{}", str, e);
                }
                if (success) {
                    REDIS.lPop(failKeyTmp);
                } else {
                    REDIS.rPoplPush(failKeyTmp, failKey);
                }
                len = len - 1;
            }
        } catch (Throwable e) {
            log.error("重新尝试失败的交易 异常", e);
        }
    }
}
