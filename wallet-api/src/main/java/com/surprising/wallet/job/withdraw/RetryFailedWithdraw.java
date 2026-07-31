package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.wallet.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 失败提现重试任务。
 * <p>
 * 每分钟执行一次：从 Redis 失败队列（withdraw:fail）拉取之前失败的提现请求，
 * 逐笔重新走提现流程（构建 withdrawal_order → 入签名队列）。
 * 若重试失败则推回到 tmp 队列避免死循环。
 *
 * @author atomex
 */
@Component
@Slf4j
public class RetryFailedWithdraw {

    /** 提现服务，重试失败请求。 */
    @Autowired
    private TransactionService txService;
    /** 全局任务开关服务。 */
    @Autowired
    private WalletRuntimeConfigService runtimeConfigService;
    /**
     * 保存 {@code redis}，用于承载当前对象的运行配置或业务数据。
     */
    @Autowired
    private StringRedisTemplate redis;
    /** Jackson 3 对象映射器，用于解析失败提现队列中的 JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 每分钟从失败队列取出待重试请求，调用提现流程，失败继续回填等待下一轮处理。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "1 1/1 * * * ?")
    public void execute() {
        if (!runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW)) {
            log.warn("失败提现重试任务跳过: global withdraw switch disabled");
            return;
        }

        String failKey = Constants.WALLET_WITHDRAW_FAIL_KEY;
        String failKeyTmp = Constants.WALLET_WITHDRAW_FAIL_KEY_TMP;

        try {
            Long len = redis.opsForList().size(failKey);
            while (len != null && len > 0) {
                log.info("重新尝试失败的交易 开始");
                String str = redis.opsForList().rightPopAndLeftPush(failKey, failKeyTmp);
                boolean success = false;
                try {
                    WithdrawRecord record = JacksonJson.readValue(objectMapper, str, WithdrawRecord.class);
                    success = txService.withdraw(record);
                } catch (Throwable e) {
                    log.error("重新尝试失败的交易 异常 记录:{}", str, e);
                }
                if (success) {
                    redis.opsForList().leftPop(failKeyTmp);
                } else {
                    redis.opsForList().rightPopAndLeftPush(failKeyTmp, failKey);
                }
                len = len - 1;
            }
        } catch (Throwable e) {
            log.error("重新尝试失败的交易 异常", e);
        }
    }
}
