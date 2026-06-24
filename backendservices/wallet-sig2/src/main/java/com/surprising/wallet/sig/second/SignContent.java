package com.surprising.wallet.sig.second;

import com.google.common.collect.Maps;
import com.surprising.wallet.common.annotation.StartThread;
import com.surprising.wallet.common.chain.RuntimeAsset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author atomex
 */
@Slf4j
@Component
public class SignContent implements InitializingBean, ApplicationContextAware {
    private static Map<String, ISignService> cache = Maps.newHashMap();
    private final int THREAD_COUNT = 5;
    private ExecutorService pool = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT * 2, 5,
            TimeUnit.MINUTES, new ArrayBlockingQueue<>(THREAD_COUNT));
    private ApplicationContext context;

    public static ISignService getSignService(RuntimeAsset currency) {
        for (Map.Entry<String, ISignService> entry : SignContent.cache.entrySet()) {

            ISignService sign = entry.getValue();

            if (sign.getCurrency().sameAsset(currency)) {
                return sign;
            }
        }
        return null;
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> threads = context.getBeansWithAnnotation(StartThread.class);
        threads.forEach((key, obj) -> {
            pool.execute((Runnable) obj);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SignContent.log.info("ExecutorService quit begin");
            pool.shutdown();
            SignContent.log.info("ExecutorService quit");
        }));
        SignContent.cache = context.getBeansOfType(ISignService.class);

    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }
}
