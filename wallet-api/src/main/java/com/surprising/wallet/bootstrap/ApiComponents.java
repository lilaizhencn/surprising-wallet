package com.surprising.wallet.bootstrap;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@WalletModeEnabled({WalletMode.api, WalletMode.all})
@ComponentScan(basePackages = {
        "com.surprising.wallet.chain",
        "com.surprising.wallet.config",
        "com.surprising.wallet.controller",
        "com.surprising.wallet.coordinator",
        "com.surprising.wallet.devfaucet",
        "com.surprising.wallet.exception",
        "com.surprising.wallet.filter",
        "com.surprising.wallet.gateway",
        "com.surprising.wallet.job",
        "com.surprising.wallet.model",
        "com.surprising.wallet.observer",
        "com.surprising.wallet.repository",
        "com.surprising.wallet.service"
})
public class ApiComponents { }
