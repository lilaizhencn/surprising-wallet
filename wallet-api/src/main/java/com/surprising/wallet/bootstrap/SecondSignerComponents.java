package com.surprising.wallet.bootstrap;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@WalletModeEnabled({WalletMode.sig2, WalletMode.all})
@ComponentScan(basePackages = "com.surprising.wallet.sig.second", nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class SecondSignerComponents {
    @Bean(name = "sig2TaskScheduler")
    ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sig2-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setAcceptTasksAfterContextClose(false);
        return scheduler;
    }
}
