package com.surprising.wallet.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/** Enforce headless signing and select credentials after application config has loaded. */
public class WalletModeEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 10; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        WalletMode mode = WalletMode.from(env);
        if (!mode.web()) {
            env.getPropertySources().addFirst(new MapPropertySource("walletSigningMode", Map.of(
                "spring.main.web-application-type", "none",
                "spring.datasource.username", env.getProperty("sw.wallet." + mode + ".db-username", "wallet_" + mode),
                "spring.datasource.password", env.getProperty("sw.wallet." + mode + ".db-password", ""),
                "spring.datasource.hikari.maximum-pool-size", env.getProperty("sw.wallet.signing.db-pool-size", "4"))));
        }
    }
}
