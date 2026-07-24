package com.surprising.wallet.config.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custody 模块 Jackson JSON 序列化配置。
 *
 * <p>提供统一的 {@link ObjectMapper} Bean，禁用时间戳序列化
 * （{@code WRITE_DATES_AS_TIMESTAMPS}），确保 API 响应中日期以 ISO-8601 字符串格式输出。
 */
@Configuration
public class CustodyJacksonConfiguration {
    /**
     * 为 custody 模块返回值提供统一的 JSON 映射器，禁止时间戳序列化。
     */
    @Bean
    public ObjectMapper custodyObjectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
