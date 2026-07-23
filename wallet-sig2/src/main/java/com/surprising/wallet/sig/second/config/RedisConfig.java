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

@Configuration
@Import(REDIS.class)
public class RedisConfig {
    @Value("${spring.data.redis.host:${spring.redis.host:127.0.0.1}}")
    protected String redisHost;
    @Value("${spring.data.redis.port:${spring.redis.port:6379}}")
    protected Integer redisPort;
    @Value("${spring.data.redis.password:${spring.redis.password:}}")
    protected String password;

    @Lazy(false)
    @Bean
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(redisHost, redisPort);
        configuration.setPassword(RedisPassword.of(password));
        return new LettuceConnectionFactory(configuration);
    }
}
