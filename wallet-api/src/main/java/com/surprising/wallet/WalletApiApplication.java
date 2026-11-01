package com.surprising.wallet;

import lombok.extern.slf4j.Slf4j;
import org.web3j.utils.Async;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import com.surprising.wallet.bootstrap.*;
import com.surprising.wallet.common.queue.PgmqConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * wallet-api 模块 Spring Boot 启动入口。
 *
 * <p>wallet-api 是整个钱包系统的 HTTP 入口层，包含 Custody REST API、
 * Console 管理后台、定时任务调度（充值扫描、提现批处理、Gas 对账、Webhook 投递等）。
 * Java 只运行 API 与业务调度；钱包签名由独立的 MPC 持有者服务完成。
 */
@Slf4j
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ApiComponents.class, PgmqConfiguration.class})
@EnableConfigurationProperties
@EnableScheduling
public class WalletApiApplication {

    /**
     * 启动应用入口，先配置 TLS 曲线后由 Spring Boot 启动整个 wallet-api 模块。
     */
    public static void main(String[] args) {
        initializeWeb3jAsyncExecutor();
        SpringApplication.run(WalletApiApplication.class, args);
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
