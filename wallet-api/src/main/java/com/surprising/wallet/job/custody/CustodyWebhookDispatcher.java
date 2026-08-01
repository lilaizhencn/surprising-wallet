package com.surprising.wallet.job.custody;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.service.CustodyWebhookDispatchService;

/**
 * 托管 Webhook 派发任务。
 * <p>
 * 每 1 秒执行一次：从 DB 拉取待发送的 webhook delivery，
 * 逐条 HTTP POST 到租户配置的回调 URL。失败时根据重试策略计算
 * 下次重试时间，达到上限后标记为最终失败。
 *
 * @author atomex
 */
@Component
public class CustodyWebhookDispatcher {
    /** 仓储：负责领取/标记 webhook 投递任务。 */
    private final CustodyWebhookDispatchService dispatcher;
    /** 防并发开关。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 构造 {@code CustodyWebhookDispatcher}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyWebhookDispatcher(CustodyWebhookDispatchService dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 每秒拉取一批待投递任务并异步执行 HTTP 回调。
     */
    @Scheduled(scheduler = "custodyTaskScheduler", fixedDelayString = "${sw.wallet.custody.webhook-dispatch-delay:1000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatcher.dispatch();
        } finally {
            running.set(false);
        }
    }
}
