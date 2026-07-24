package com.surprising.wallet.sig.second.config;

import com.surprising.starters.redis.REDIS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * sig2 模块的 Redis 连接配置。
 *
 * <p>创建响应式 Redis 连接工厂 (Lettuce)，供签名任务从 Redis 队列
 * 拉取待签交易和推送签名结果。
 */
@Configuration
@Import(REDIS.class)
public class RedisConfig {

    /** Redis 主机地址 */
    @Value("${spring.data.redis.host:${spring.redis.host:127.0.0.1}}")
    protected String redisHost;
    /** Redis 端口 */
    @Value("${spring.data.redis.port:${spring.redis.port:6379}}")
    protected Integer redisPort;
    /** Redis 密码 */
    @Value("${spring.data.redis.password:${spring.redis.password:}}")
    protected String password;

    /**
     * 创建响应式 Redis 连接工厂。
     *
     * @return Lettuce 响应式连接工厂
     */
    @Lazy(false)
    @Bean
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(redisHost, redisPort);
        configuration.setPassword(RedisPassword.of(password));
        return new LettuceConnectionFactory(configuration);
    }
}
