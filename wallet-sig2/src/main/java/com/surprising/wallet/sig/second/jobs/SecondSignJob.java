package com.surprising.wallet.sig.second.jobs;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.second.ISignService;
import com.surprising.wallet.sig.second.SignContent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 第二次签名服务（sig2）。
 * <p>
 * 每 10 秒执行一次：从 Redis "二次签名队列"（sig:second）拉取已完成一次签名的交易，
 * 调用对应链的签名算法补充第二次签名，拼装完整签名交易（rawTransaction），
 * 推送到 Redis "签名完成队列"（sig:done），由 {@code SendRawTxJob} 广播上链。
 * <p>
 * sig1 + sig2 双重签名是为了满足多签安全模型：两个进程独立部署、独立持有密钥分片，
 * 任一进程被攻破都无法单方面签名交易。
 *
 * @author atomex
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SecondSignJob {

    /**
     * 定义 {@code DELAY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Duration DELAY = Duration.ofSeconds(10);
    /**
     * 定义 {@code HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final HexFormat HEX = HexFormat.of();

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
     * 执行 {@code schedule} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @PostConstruct
    void schedule() {
        taskScheduler.scheduleWithFixedDelay(this::execute, DELAY);
        log.info("SecondSignJob scheduled with fixed delay {}ms", DELAY.toMillis());
    }

    /**
     * 执行或处理 {@code execute} 对应的业务流程，并维护状态和异常边界。
     */
    void execute() {
        String key = Constants.WALLET_WITHDRAW_SIG_SECOND_KEY;
        String tmp = Constants.WALLET_WITHDRAW_SIG_SECOND_TMP_KEY;

        try {
            String txStr = redis.opsForList().rightPopAndLeftPush(key, tmp);
            if (ObjectUtils.isEmpty(txStr)) {
                return;
            }

            WithdrawTransaction transaction = JacksonJson.readValue(objectMapper, txStr, WithdrawTransaction.class);
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = SignContent.getSignService(currency);
            ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
            if (signService == null) {
                signature.put("valid", false);
                signature.put("error", "no sign service for " + currency.getName());
            } else {
                String rawTransaction = signService.signTransaction(transaction);
                signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
                if (StringUtils.hasText(rawTransaction)) {
                    Transaction signedTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(rawTransaction)));
                    signature.put("rawTransaction", rawTransaction);
                    signature.put("txId", signedTx.getTxId().toString());
                    signature.put("weight", signedTx.getWeight());
                    signature.put("vBytes", signedTx.getVsize());
                    signature.remove("firstSignTx");
                    signature.put("valid", true);
                    log.info("二次签名成功 txId={}, finalTxId={}, weight={}, vBytes={}",
                            transaction.getId(), signedTx.getTxId(), signedTx.getWeight(), signedTx.getVsize());
                } else {
                    signature.put("valid", false);
                    if (!signature.has("error")) {
                        signature.put("error", "second sign returned empty raw transaction");
                    }
                    log.warn("二次签名失败 txId={}, error={}", transaction.getId(), JacksonJson.text(signature, "error"));
                }
            }
            transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
            redis.opsForList().leftPush(
                    Constants.WALLET_WITHDRAW_SIG_DONE_KEY, JacksonJson.writeValue(objectMapper, transaction));
            redis.opsForList().leftPop(tmp);

        } catch (DataAccessException e) {
            log.info("Signature second job redis error", e);
        } catch (Throwable e) {
            log.error("Signature second job error, will retry", e);
        }
    }
}
