package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class GetWithdrawRecordJob {
/**
 * 提现请求拉取任务。
 * <p>
 * 每 30 秒执行一次：从 Redis "待提现队列" 批量拉取用户提现请求，
 * 调用 TransactionService 执行提现流程（构建 withdrawal_order → 入签名队列）。
 * 失败的请求写入失败队列等待重试。
 *
 * @author atomex
 */

    @Autowired
    private TransactionService txService;

    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 30_000)
    public void run() {
        String key = Constants.WALLET_WITHDRAW_WAIT_KEY;
        String failKey = Constants.WALLET_WITHDRAW_FAIL_KEY;
        try {
            long count = 100L;
            List<String> withdrawStr = REDIS.lRange(key, 0L, count);
            if (!CollectionUtils.isEmpty(withdrawStr)) {
                Set<WithdrawRecord> withdrawRecordSet = withdrawStr.parallelStream().map(str -> {
                    JSONObject json = JSONObject.parseObject(str);
                    WithdrawRecord withdrawRecord = json.toJavaObject(WithdrawRecord.class);
                    log.info("打印提现请求:{}", withdrawRecord);
                    return withdrawRecord;
                }).collect(Collectors.toSet());
                withdrawRecordSet.parallelStream().forEach(record -> {
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
            }
        } catch (DataAccessException e) {
            log.info("get waiting for withdraw error", e);
        } catch (Throwable e) {
            log.info("get waiting for withdraw quit", e);
        }
    }
}
