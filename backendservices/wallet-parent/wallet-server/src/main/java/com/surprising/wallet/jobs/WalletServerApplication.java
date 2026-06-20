package com.surprising.wallet.jobs;

import com.surprising.wallet.common.annotation.StartThread;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author lilaizhen
 */
@Slf4j
@SpringBootApplication(scanBasePackages = {"com.surprising.wallet", "com.surprising.starters"})
@EnableConfigurationProperties
@EnableScheduling
public class WalletServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServerApplication.class, args);
    }

    @Autowired
    private ApplicationContext context;
    private final int THREAD_COUNT = 5;
    private final ExecutorService POOL = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT * 2, 5, TimeUnit.MINUTES, new ArrayBlockingQueue<>(THREAD_COUNT));

    @EventListener(classes = ApplicationStartedEvent.class)
    public void threadInit() {
        Map<String, Object> threads = context.getBeansWithAnnotation(StartThread.class);
        threads.forEach((key, obj) -> POOL.execute((Runnable) obj));
        log.info("启动jobs线程完成");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            POOL.shutdown();
            log.info("jobs线程池shutdown完成");
        }));
    }
}
