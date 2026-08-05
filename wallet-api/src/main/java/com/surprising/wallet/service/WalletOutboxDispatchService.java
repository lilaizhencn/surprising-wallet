package com.surprising.wallet.service;

import com.surprising.wallet.repository.WalletOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** 钱包 Outbox 派发服务，负责把已提交的签名任务可靠投递到 Redis。 */
@Slf4j
@Service
public class WalletOutboxDispatchService {
    /** 首次签名 Outbox 主题。 */
    public static final String SIGNING_FIRST_TOPIC = "SIGNING_FIRST";
    /** 二次签名 Outbox 主题。 */
    public static final String SIGNING_SECOND_TOPIC = "SIGNING_SECOND";
    /** Outbox 最大自动重试次数。 */
    private static final int MAX_ATTEMPTS = 20;
    /** Outbox 租约工作者标识。 */
    private final String workerId;
    /** Outbox 仓储。 */
    private final WalletOutboxRepository outbox;
    /** Redis 模板。 */
    private final StringRedisTemplate redis;

    /** 构造 Outbox 派发服务。 */
    public WalletOutboxDispatchService(WalletOutboxRepository outbox,
                                       StringRedisTemplate redis,
                                       WalletTaskLeaseService leaseService) {
        this.outbox = outbox;
        this.redis = redis;
        this.workerId = leaseService.ownerId() + "-outbox";
    }

    /** 派发一个主题的待处理消息。 */
    public void dispatch(String topic, String redisKey) {
        List<WalletOutboxRepository.OutboxRecord> records = outbox.claim(
                topic, workerId, 100);
        for (WalletOutboxRepository.OutboxRecord record : records) {
            try {
                redis.opsForList().leftPush(redisKey, record.payload());
                outbox.markDispatched(record.id(), workerId);
            } catch (RuntimeException error) {
                boolean dead = record.attemptCount() >= MAX_ATTEMPTS;
                Duration delay = Duration.ofSeconds(Math.min(900, 1L << Math.min(record.attemptCount(), 9)));
                outbox.markFailed(record.id(), workerId, truncate(error.getMessage()), delay, dead);
                log.warn("wallet outbox dispatch failed: topic={} id={} attempts={} dead={}",
                        topic, record.id(), record.attemptCount(), dead, error);
            }
        }
    }

    /** 截断外部组件错误，避免错误信息无限膨胀。 */
    private static String truncate(String message) {
        if (message == null || message.isBlank()) return "outbox dispatch failed";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
