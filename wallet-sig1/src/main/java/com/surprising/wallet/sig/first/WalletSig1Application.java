package com.surprising.wallet.sig.first;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 第一签名服务（sig1）Spring Boot 启动入口。
 *
 * <p>sig1 持有第一组密钥分片，负责对提现/归集交易生成第一次部分签名。
 * 完成后将交易推送到 Redis 二签队列，由 sig2 完成最终签名。
 *
 * <p>sig1 和 sig2 独立部署，各自持有不同的 BIP32 密钥分片，
 * 任一服务被攻破都无法单方面签名交易，满足多签安全模型。
 */
@EnableScheduling
@SpringBootApplication(
        scanBasePackages = {
                "com.surprising"
        }
)
public class WalletSig1Application {

    /**
     * 应用启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WalletSig1Application.class, args);
    }

}

