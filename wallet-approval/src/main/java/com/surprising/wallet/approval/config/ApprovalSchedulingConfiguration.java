package com.surprising.wallet.approval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration @EnableScheduling
public class ApprovalSchedulingConfiguration {
    @Bean public ThreadPoolTaskScheduler approvalExpiryScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("approval-expiry-");
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(10);return scheduler;
    }
}
