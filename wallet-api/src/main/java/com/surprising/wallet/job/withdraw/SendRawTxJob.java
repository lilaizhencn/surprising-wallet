package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class SendRawTxJob {
/**
 * 链上交易广播任务。
 * <p>
 * 每 30 秒执行一次：从 Redis "签名完成队列"（sig:done）拉取已完成两次签名的
 * 交易，逐笔调用链 RPC 广播上链。广播失败的交易重新推回队列尾部等待重试。
 *
 * @author atomex
 */

    private static final long COUNT = 100L;

    @Autowired
    private TransactionService txService;

    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 30_000)
    public void run() {
        String key = Constants.WALLET_WITHDRAW_SIG_DONE_KEY;
        try {
            List<String> withdrawStr = REDIS.lRange(key, 0L, COUNT);
            if (!CollectionUtils.isEmpty(withdrawStr)) {
                log.info("广播交易开始 待广播数量:{}", withdrawStr.size());
                java.util.ArrayList<String> retry = new java.util.ArrayList<>();
                withdrawStr.forEach(str -> {
                    JSONObject json = JSONObject.parseObject(str);
                    WithdrawTransaction transaction = json.toJavaObject(WithdrawTransaction.class);
                    if (!txService.sendWithdrawTransaction(transaction)) {
                        retry.add(str);
                    }
                });
                log.info("广播交易结束 数量:{}", withdrawStr.size());
                REDIS.lTrim(key, withdrawStr.size(), -1L);
                retry.forEach(str -> REDIS.rPush(key, str));
            }
        } catch (DataAccessException | JsonRpcClientException e) {
            log.info("广播交易调用rpc响应错误", e);
        } catch (Throwable e) {
            log.info("广播交易异常", e);
        }
    }
}
