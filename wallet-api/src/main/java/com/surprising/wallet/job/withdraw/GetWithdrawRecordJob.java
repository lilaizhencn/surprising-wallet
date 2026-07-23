package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.annotation.StartThread;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author atomex
 * withdrawJob 要持续运行,所以不用定时任务
 * 去从队列中获取待提现记录
 */
@Component
@StartThread
@Slf4j
public class GetWithdrawRecordJob implements Runnable {

    @Autowired
    private TransactionService txService;

    @Override
    public void run() {
        log.info("获取待提现消息列表开始");
        String key = Constants.WALLET_WITHDRAW_WAIT_KEY;
        String failKey = Constants.WALLET_WITHDRAW_FAIL_KEY;
        while (true) {
            try {

                long count = 100L;
                List<String> withdrawStr = REDIS.lRange(key, 0L, count);
                if (!CollectionUtils.isEmpty(withdrawStr)) {
                    Set<WithdrawRecord> withdrawRecordSet = withdrawStr.parallelStream().map((str) -> {
                        JSONObject json = JSONObject.parseObject(str);
                        WithdrawRecord withdrawRecord = json.toJavaObject(WithdrawRecord.class);
                        log.info("打印提现请求:{}", withdrawRecord);
                        return withdrawRecord;
                    }).collect(Collectors.toSet());
                    withdrawRecordSet.parallelStream().forEach((record) -> {
                        boolean success = false;
                        try {
                            success = txService.withdraw(record);
                        } catch (Throwable e) {
                            log.error("执行提现服务失败 币种:{} 提现地址:{}", record.getCurrency(), record.getAddress(), e);
                        }
                        if (!success) {
                            REDIS.lPush(failKey, JSONObject.toJSONString(record));
                        }
                    });
                    REDIS.lTrim(key, withdrawStr.size(), -1L);
                    continue;
                }
            } catch (DataAccessException e) {
                e.printStackTrace();
                log.info("get waiting for withdraw error", e);

            } catch (Throwable e) {
                e.printStackTrace();
                log.info("get waiting for withdraw quit", e);
                break;
            }
            try {
                long waitTime = 30 * 1000L;
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                log.info("get waiting for withdraw quit", e);
                break;
            }
        }
    }
}
