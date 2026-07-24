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

/**
 * 签名服务注册表，持有所有 {@link ISignService} 实现。
 *
 * <p>在 Spring 容器初始化完成后自动扫描所有 ISignService bean，
 * 按链/资产名称缓存。调用方通过 {@link #getSignService(AssetRuntimeMetadata)}
 * 即可获取匹配当前币种的签名服务，无需硬编码链与实现类的映射。
 *
 * <p>查找逻辑：遍历全部注册的签名服务，由 {@link ISignService#supports(AssetRuntimeMetadata)}
 * 判断是否匹配当前资产（按 chain + assetSymbol 匹配）。
 */
@Slf4j
@Component
public class SignContent implements InitializingBean, ApplicationContextAware {

    /** 签名服务缓存，key 为 bean 名称，value 为签名服务实现。 */
    private static Map<String, ISignService> cache = Maps.newHashMap();
    /** Spring 应用上下文，用于获取所有 ISignService bean。 */
    private ApplicationContext context;

    /**
     * 根据资产元数据查找匹配的签名服务。
     *
     * @param currency 资产运行时元数据（包含链和币种信息）
     * @return 匹配的签名服务，未找到时返回 null
     */
    public static ISignService getSignService(AssetRuntimeMetadata currency) {
        for (Map.Entry<String, ISignService> entry : SignContent.cache.entrySet()) {
            ISignService sign = entry.getValue();
            if (sign.supports(currency)) {
                return sign;
            }
        }
        return null;
    }

    /**
     * Spring 容器初始化完成后，扫描所有 ISignService bean 并缓存。
     * 调用时机由 {@link InitializingBean} 约定保证。
     */
    @Override
    public void afterPropertiesSet() {
        SignContent.cache = context.getBeansOfType(ISignService.class);
    }

    /**
     * 注入 Spring 应用上下文。
     *
     * @param applicationContext Spring 应用上下文
     * @throws BeansException 注入失败时抛出
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }
}
