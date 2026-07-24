package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.wallet.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 链上交易广播任务。
 * <p>
 * 从 Redis 签名完成队列（sig:done）拉取交易，逐笔发送到链网关。
 * 发送失败交易会回写到队列尾部，等待下一轮重试。
 */
@Component
@Slf4j
public class BroadCastSignedTxJob {
    /** 每批次最多读取并尝试广播的交易数量。 */
    private static final long COUNT = 100L;

    /** 交易服务，负责链上广播动作。 */
    @Autowired
    private TransactionService txService;

    /**
     * 每 30 秒执行一次广播流程：读取已签名交易、逐笔广播、失败项回填队列。
     */
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
