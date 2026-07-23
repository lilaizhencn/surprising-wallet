package com.surprising.wallet.sig.first;

import com.google.common.collect.Maps;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.sig.first.service.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class SignContent implements InitializingBean, ApplicationContextAware {

    private ApplicationContext context;
    private Map<String, ISignService> cache = Maps.newHashMap();

    public ISignService getSignService(AssetRuntimeMetadata currency) {
        return cache.values().stream().filter(sign -> sign.supports(currency)).findFirst().orElse(null);
    }

    @Override
    public void afterPropertiesSet() {
        cache = context.getBeansOfType(ISignService.class);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }
}
