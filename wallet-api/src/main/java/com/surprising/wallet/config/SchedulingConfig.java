package com.surprising.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("custody-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    /**
     * EIP-7702 批量归集/提现工作流专用池（偏 I/O 密集，默认 3~5 秒间隔）。
     */
    @Bean(name = "evm7702TaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler evm7702TaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(3);
        s.setThreadNamePrefix("evm7702-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }

    /**
     * Account-Chain 全链路任务池：充值扫描、提现处理、归集、确认等。
     */
    @Bean(name = "accountTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler accountTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(5);
        s.setThreadNamePrefix("account-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }

    /**
     * 区块扫描任务池：UTXO 链扫描器按固定周期推进链上高度。
     */
    @Bean(name = "depositTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler depositTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("deposit-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }

    /**
     * 提现链路专用池：提单出池、签名重放、广播、RBF 及 fee rate 更新。
     */
    @Bean(name = "withdrawTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler withdrawTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(8);
        s.setThreadNamePrefix("withdraw-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }
}
