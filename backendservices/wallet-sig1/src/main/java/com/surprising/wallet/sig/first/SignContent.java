package com.surprising.wallet.sig.first;

import com.google.common.collect.Maps;
import com.surprising.wallet.common.annotation.StartThread;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.sig.first.service.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author lilaizhen
 * @data 01/04/2018
 */
@Slf4j
@Component
public class SignContent implements InitializingBean, ApplicationContextAware, ApplicationListener<ApplicationStartedEvent> {
    private final int THREAD_COUNT = 5;
    ExecutorService pool = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT * 2, 5,
            TimeUnit.MINUTES, new ArrayBlockingQueue<>(THREAD_COUNT));
    private ApplicationContext context;
    private Map<String, ISignService> cache = Maps.newHashMap();


    public ISignService getSignService(RuntimeAsset currency) {
        return cache.values().stream().filter(sign -> sign.getCurrency() == currency).findFirst().orElse(null);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        Map<String, Object> threads = context.getBeansWithAnnotation(StartThread.class);
        threads.forEach((key, obj) -> {
            pool.execute((Runnable) obj);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("ExecutorService quit begin");
            pool.shutdown();
            log.info("ExecutorService quit");
        }));
        cache = context.getBeansOfType(ISignService.class);
    }
}
