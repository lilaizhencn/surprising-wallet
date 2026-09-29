package com.surprising.wallet.config;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(com.surprising.wallet.common.queue.PgmqConfiguration.class)
public class WalletQueueConfiguration { }
