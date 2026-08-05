package com.surprising.wallet;

import lombok.extern.slf4j.Slf4j;
import org.web3j.utils.Async;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * wallet-api 模块 Spring Boot 启动入口。
 *
 * <p>wallet-api 是整个钱包系统的 HTTP 入口层，包含 Custody REST API、
 * Console 管理后台、定时任务调度（充值扫描、提现批处理、Gas 对账、Webhook 投递等）。
 * 组件扫描仅覆盖 {@code com.surprising.wallet}。
 *
 * <p>启动时配置 TLS 命名组以兼容旧版签名库。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.surprising.wallet")
@EnableConfigurationProperties
@EnableScheduling
public class WalletApiApplication {

    /**
     * 启动应用入口，先配置 TLS 曲线后由 Spring Boot 启动整个 wallet-api 模块。
     */
    static void main(String[] args) {
        configureTlsNamedGroups();
        initializeWeb3jAsyncExecutor();
        SpringApplication.run(WalletApiApplication.class, args);
    }

    /**
     * 绑定指定 TLS 命名组，兼容老版本签名库和 JVM 环境。
     */
    private static void configureTlsNamedGroups() {
        System.setProperty("jdk.tls.namedGroups",
                "secp256r1,secp384r1,secp521r1,ffdhe2048,ffdhe3072");
    }

    /**
     * 在应用启动阶段初始化 Web3j 异步执行器。
     *
     * <p>Web3j 默认会在第一次链调用时注册 JVM shutdown hook。提前初始化可以避免服务收到停止信号后，
     * 正在退出的 JVM 又尝试注册新的 shutdown hook。</p>
     */
    private static void initializeWeb3jAsyncExecutor() {
        Async.defaultExecutorService();
    }
}
