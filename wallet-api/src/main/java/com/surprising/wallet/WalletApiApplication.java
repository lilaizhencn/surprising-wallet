package com.surprising.wallet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication(scanBasePackages = {"com.surprising.wallet", "com.surprising.starters"})
@EnableConfigurationProperties
@EnableScheduling
public class WalletApiApplication {

    /**
     * 启动应用入口，先配置 TLS 曲线后由 Spring Boot 启动整个 wallet-api 模块。
     */
    public static void main(String[] args) {
        configureTlsNamedGroups();
        SpringApplication.run(WalletApiApplication.class, args);
    }

    /**
     * 绑定指定 TLS 命名组，兼容老版本签名库和 JVM 环境。
     */
    private static void configureTlsNamedGroups() {
        System.setProperty("jdk.tls.namedGroups",
                "secp256r1,secp384r1,secp521r1,ffdhe2048,ffdhe3072");
    }
}
