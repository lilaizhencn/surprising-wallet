package com.surprising.wallet.common.queue;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PgmqConfiguration {
    @Bean public PgmqClient pgmqClient(JdbcTemplate jdbc) { return new PgmqClient(jdbc); }
    @Bean public QueueWorker queueWorker(PgmqClient client, PlatformTransactionManager manager) {
        return new QueueWorker(client, manager);
    }
}
