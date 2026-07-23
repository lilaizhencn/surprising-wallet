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

    public static void main(String[] args) {
        configureTlsNamedGroups();
        SpringApplication.run(WalletApiApplication.class, args);
    }

    private static void configureTlsNamedGroups() {
        System.setProperty("jdk.tls.namedGroups",
                "secp256r1,secp384r1,secp521r1,ffdhe2048,ffdhe3072");
    }
}
