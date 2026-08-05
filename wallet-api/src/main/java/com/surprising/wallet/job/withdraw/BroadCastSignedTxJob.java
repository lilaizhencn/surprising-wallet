package com.surprising.wallet.job.withdraw;

import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


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
    private static final int COUNT = 100;
    /** 广播任务的处理中队列，任务进程崩溃后可以恢复。 */
    private static final String PROCESSING_SUFFIX = ":processing";

    /** 交易服务，负责链上广播动作。 */
    private final TransactionService txService;
    /**
     * 保存 {@code redis}，用于承载当前对象的运行配置或业务数据。
     */
    private final StringRedisTemplate redis;
    /** Jackson 3 对象映射器，用于解析签名完成队列中的交易 JSON。 */
    private final ObjectMapper objectMapper;

    /** 构造链上交易广播任务。 */
    public BroadCastSignedTxJob(TransactionService txService,
                                StringRedisTemplate redis,
                                ObjectMapper objectMapper) {
        this.txService = txService;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 每 30 秒执行一次广播流程：读取已签名交易、逐笔广播、失败项回填队列。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", fixedDelay = 30_000)
    public void run() {
        String key = Constants.WALLET_WITHDRAW_SIG_DONE_KEY;
        String processing = key + PROCESSING_SUFFIX;
        try {
            recoverProcessing(key, processing);
            int processed = 0;
            while (processed++ < COUNT) {
                String value = redis.opsForList().rightPopAndLeftPush(key, processing);
                if (value == null || value.isBlank()) {
                    break;
                }
                boolean retry = false;
                try {
                    WithdrawTransaction transaction = JacksonJson.readValue(objectMapper, value, WithdrawTransaction.class);
                    retry = !txService.sendWithdrawTransaction(transaction);
                } catch (DataAccessException | JsonRpcClientException error) {
                    retry = true;
                    log.warn("广播交易调用 RPC 失败，将任务放回队列", error);
                } catch (Throwable error) {
                    retry = true;
                    log.warn("广播交易异常，将任务放回队列", error);
                } finally {
                    redis.opsForList().remove(processing, 1, value);
                    if (retry) {
                        redis.opsForList().rightPush(key, value);
                    }
                }
            }
        } catch (Throwable e) {
            log.warn("广播交易队列处理异常", e);
        }
    }

    /** 将上一次进程崩溃遗留的处理中任务恢复到主队列。 */
    private void recoverProcessing(String key, String processing) {
        while (true) {
            String value = redis.opsForList().rightPopAndLeftPush(processing, key);
            if (value == null) {
                return;
            }
        }
    }
}
