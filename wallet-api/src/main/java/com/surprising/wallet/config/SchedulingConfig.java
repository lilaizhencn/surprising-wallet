package com.surprising.wallet.config;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 按任务类型隔离调度线程池，避免单个慢任务影响其他定时任务。
 *
 * <p>每个 Job 都应通过 {@code @Scheduled(scheduler = "...")} 显式指定池名，
 * 便于链路级限流和故障隔离。</p>
 *
 * <p>未显式指定调度器的任务使用全局配置
 * {@code spring.task.scheduling.pool.size}。</p>
 */
@Configuration
public class SchedulingConfig {

    /**
     * 托管清结算与派发类任务（默认 500ms~2s）。
     * 主要用于 webhook 派发、提现状态对账、gas 结算等低延迟任务。
     */
    @Bean(name = "custodyTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler custodyTaskScheduler() {
        return createScheduler(4, "custody-scheduler-", 30);
    }

    /**
     * EIP-7702 批量归集/提现工作流专用池（偏 I/O 密集，默认 3~5 秒间隔）。
     */
    @Bean(name = "evm7702TaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler evm7702TaskScheduler() {
        return createScheduler(3, "evm7702-scheduler-", 60);
    }

    /**
     * Account-Chain 全链路任务池：充值扫描、提现处理、归集、确认等。
     */
    @Bean(name = "accountTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler accountTaskScheduler() {
        return createScheduler(5, "account-scheduler-", 60);
    }

    /**
     * 区块扫描任务池：UTXO 链扫描器按固定周期推进链上高度。
     */
    @Bean(name = "depositTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler depositTaskScheduler() {
        return createScheduler(4, "deposit-scheduler-", 60);
    }

    /**
     * 提现链路专用池：提单出池、签名重放、广播、RBF 及 fee rate 更新。
     */
    @Bean(name = "withdrawTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler withdrawTaskScheduler() {
        return createScheduler(8, "withdraw-scheduler-", 60);
    }

    /** Webhook 投递执行池，避免单个租户的慢回调阻塞其他租户。 */
    @Bean(name = "custodyWebhookExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor custodyWebhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("custody-webhook-");
        executor.setAcceptTasksAfterContextClose(false);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 创建具有统一关闭策略的定时任务线程池。
     *
     * <p>关闭上下文后不再接收新任务，也不继续执行已经排队的周期任务；正在执行的任务在限定时间内完成，
     * 避免依赖的 RPC、Redis 或数据库连接已经销毁后，定时任务又发起新的调用。</p>
     *
     * @param poolSize 线程池大小
     * @param threadNamePrefix 线程名前缀
     * @param awaitTerminationSeconds 等待正在执行任务的最长秒数
     * @return 配置完成的定时任务线程池
     */
    private ThreadPoolTaskScheduler createScheduler(
            int poolSize, String threadNamePrefix, int awaitTerminationSeconds) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(awaitTerminationSeconds);
        return scheduler;
    }

    /**
     * 在 Spring 开始销毁业务依赖前，先停止所有业务任务池。
     *
     * <p>ContextClosedEvent 发生在单例销毁之前。并行关闭各个任务池可以避免某一个慢链 RPC 独占关闭预算，
     * 同时保证任务不会在 Web3j、Redis 等依赖关闭后再次启动。</p>
     */
    @Bean(name = "walletTaskSchedulerShutdownCoordinator")
    public WalletTaskSchedulerShutdownCoordinator walletTaskSchedulerShutdownCoordinator(
            List<ThreadPoolTaskScheduler> taskSchedulers,
            ThreadPoolTaskExecutor custodyWebhookExecutor) {
        return new WalletTaskSchedulerShutdownCoordinator(taskSchedulers, custodyWebhookExecutor);
    }

    /**
     * 负责协调钱包任务池的优雅关闭。
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static final class WalletTaskSchedulerShutdownCoordinator
            implements ApplicationListener<ContextClosedEvent> {

        /** 定时任务线程池。 */
        private final List<ThreadPoolTaskScheduler> taskSchedulers;

        /** Webhook 投递线程池。 */
        private final ThreadPoolTaskExecutor custodyWebhookExecutor;

        /** 保证关闭事件重复发布时只执行一次。 */
        private final AtomicBoolean shutdownStarted = new AtomicBoolean();

        /**
         * 创建关闭协调器。
         *
         * @param taskSchedulers 定时任务线程池
         * @param custodyWebhookExecutor Webhook 投递线程池
         */
        private WalletTaskSchedulerShutdownCoordinator(
                List<ThreadPoolTaskScheduler> taskSchedulers,
                ThreadPoolTaskExecutor custodyWebhookExecutor) {
            this.taskSchedulers = List.copyOf(taskSchedulers);
            this.custodyWebhookExecutor = custodyWebhookExecutor;
        }

        /**
         * 在 Spring 销毁业务 Bean 前并行停止任务池。
         *
         * @param event Spring 上下文关闭事件
         */
        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            if (!shutdownStarted.compareAndSet(false, true)) {
                return;
            }

            CompletableFuture<?>[] shutdowns = new CompletableFuture<?>[taskSchedulers.size() + 1];
            for (int i = 0; i < taskSchedulers.size(); i++) {
                ThreadPoolTaskScheduler scheduler = taskSchedulers.get(i);
                shutdowns[i] = CompletableFuture.runAsync(scheduler::shutdown);
            }
            shutdowns[taskSchedulers.size()] = CompletableFuture.runAsync(custodyWebhookExecutor::shutdown);
            CompletableFuture.allOf(shutdowns).join();
        }
    }
}
