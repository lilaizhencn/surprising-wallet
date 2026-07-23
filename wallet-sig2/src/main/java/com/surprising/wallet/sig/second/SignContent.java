package com.surprising.wallet.sig.second;

import com.google.common.collect.Maps;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
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

    private static Map<String, ISignService> cache = Maps.newHashMap();
    private ApplicationContext context;

    public static ISignService getSignService(AssetRuntimeMetadata currency) {
        for (Map.Entry<String, ISignService> entry : SignContent.cache.entrySet()) {
            ISignService sign = entry.getValue();
            if (sign.supports(currency)) {
                return sign;
            }
        }
        return null;
    }

    @Override
    public void afterPropertiesSet() {
        SignContent.cache = context.getBeansOfType(ISignService.class);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }
}
