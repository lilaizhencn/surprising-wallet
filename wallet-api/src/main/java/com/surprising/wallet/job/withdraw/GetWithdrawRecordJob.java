package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.wallet.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提现请求拉取任务。
 * <p>
 * 每 30 秒执行一次：从 Redis 待提现队列读取提现请求，触发提现编排，
 * 失败的请求写入失败队列等待重试。
 */
@Component
@Slf4j
public class GetWithdrawRecordJob {
    /** 提现交易服务，负责把请求转为待签名订单。 */
    @Autowired
    private TransactionService txService;
    /**
     * 保存 {@code redis}，用于承载当前对象的运行配置或业务数据。
     */
    @Autowired
    private StringRedisTemplate redis;

    /**
     * 执行一次取数与提交：读取待提现队列、去重、入流水并对失败请求回写重试队列。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 30_000)
    public void run() {
        String key = Constants.WALLET_WITHDRAW_WAIT_KEY;
        String failKey = Constants.WALLET_WITHDRAW_FAIL_KEY;
        try {
            long count = 100L;
            List<String> withdrawStr = redis.opsForList().range(key, 0L, count);
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
                        redis.opsForList().leftPush(failKey, JSONObject.toJSONString(record));
                    }
                });
                redis.opsForList().trim(key, withdrawStr.size(), -1L);
            }
        } catch (DataAccessException e) {
            log.info("get waiting for withdraw error", e);
        } catch (Throwable e) {
            log.info("get waiting for withdraw quit", e);
        }
    }
}
