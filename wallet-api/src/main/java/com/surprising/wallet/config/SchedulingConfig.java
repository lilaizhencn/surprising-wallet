package com.surprising.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated thread pools per job category to prevent slow or stuck jobs from
 * starving unrelated schedulers.
 * <p>
 * Jobs reference their scheduler by bean name:
 * {@code @Scheduled(scheduler = "custodyTaskScheduler", ...)}.
 * <p>
 * The default pool (for jobs without an explicit scheduler) is configured via
 * {@code spring.task.scheduling.pool.size} in application.yaml.
 */
@Configuration
public class SchedulingConfig {

    /** Fast custody reconciliation / dispatch jobs (500ms–2s fixed delay). */
    @Bean(name = "custodyTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler custodyTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("custody-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    /** EIP-7702 collection / withdrawal workflow (3s–5s fixed delay, IO-heavy). */
    @Bean(name = "evm7702TaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler evm7702TaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(3);
        s.setThreadNamePrefix("evm7702-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }

    /** Account-chain workflow jobs (deposit scan / withdrawal / collection cron). */
    @Bean(name = "accountTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler accountTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(5);
        s.setThreadNamePrefix("account-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }

    /** Deposit block-scanning jobs (UTXO chains, each ~59s cron). */
    @Bean(name = "depositTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler depositTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("deposit-scheduler-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        return s;
    }

    /** Withdraw batch / signing / fee-rate jobs (cron 30s–2min). */
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
