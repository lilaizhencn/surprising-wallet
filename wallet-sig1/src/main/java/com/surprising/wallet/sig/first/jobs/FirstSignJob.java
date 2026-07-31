package com.surprising.wallet.sig.first.jobs;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.sig.first.SignContent;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.first.service.ISignService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * 第一次签名服务（sig1）。
 * <p>
 * 每 10 秒执行一次：从 Redis "一次签名队列"（sig:first）拉取待签名的提现/归集交易，
 * 调用对应链的签名算法（ECDSA / EdDSA / Schnorr）生成首次签名，
 * 完成后推送到 Redis "二次签名队列"（sig:second），签名失败则直接推送到完成队列。
 * <p>
 * sig1 + sig2 双重签名是为了满足多签安全模型：sig1 持有部分密钥分片，
 * sig2 持有另一部分，只有两方都签名交易才有效。
 *
 * @author atomex
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FirstSignJob {

    /**
     * 定义 {@code DELAY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Duration DELAY = Duration.ofSeconds(10);

    /**
     * 保存 {@code taskScheduler}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TaskScheduler taskScheduler;
    /**
     * 保存 {@code redis}，用于承载当前对象的运行配置或业务数据。
     */
    private final StringRedisTemplate redis;
    /** Jackson 3 对象映射器，用于处理 Redis 队列中的交易 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 保存 {@code signContent}，用于承载当前对象的运行配置或业务数据。
     */
    @Autowired
    SignContent signContent;

    /**
     * 执行 {@code schedule} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @PostConstruct
    void schedule() {
        taskScheduler.scheduleWithFixedDelay(this::execute, DELAY);
        log.info("FirstSignJob scheduled with fixed delay {}ms", DELAY.toMillis());
    }

    /**
     * 执行或处理 {@code execute} 对应的业务流程，并维护状态和异常边界。
     */
    void execute() {
        String key = Constants.WALLET_WITHDRAW_SIG_FIRST_KEY;
        String tmp = Constants.WALLET_WITHDRAW_SIG_FIRST_TMP_KEY;

        try {
            String txStr = redis.opsForList().rightPopAndLeftPush(key, tmp);
            if (ObjectUtils.isEmpty(txStr)) {
                return;
            }

            log.info("获取到的第一次交易的数据:{}", txStr);
            ObjectNode txJson = JacksonJson.readObject(objectMapper, txStr);
            WithdrawTransaction transaction = JacksonJson.toValue(objectMapper, txJson, WithdrawTransaction.class);
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = signContent.getSignService(currency);
            if (signService == null) {
                ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
                signature.put("valid", false);
                signature.put("error", "no first sign service for " + currency.getName());
                transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
            } else {
                signService.signTransaction(transaction);
            }
            String signatureStr = transaction.getSignature();
            ObjectNode sigJson = JacksonJson.readObject(objectMapper, signatureStr);
            String rKey;
            if (JacksonJson.booleanValue(sigJson, "valid")) {
                log.info("签名验证成功 开始推送到第二次签名服务队列");
                rKey = Constants.WALLET_WITHDRAW_SIG_SECOND_KEY;
            } else {
                log.warn("签名验证失败 推送到签名失败队列");
                rKey = Constants.WALLET_WITHDRAW_SIG_DONE_KEY;
            }
            redis.opsForList().leftPush(rKey, JacksonJson.writeValue(objectMapper, transaction));
            redis.opsForList().leftPop(tmp);
            log.info("签名验证推送完成 key:{}", rKey);

        } catch (DataAccessException e) {
            log.info("Signature first job error", e);
        } catch (Throwable e) {
            log.error("Signature first job error, will retry", e);
        }
    }
}
