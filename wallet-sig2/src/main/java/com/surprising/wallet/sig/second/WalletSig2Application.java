package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

/**
 * 第二签名服务（sig2）Spring Boot 启动入口。
 *
 * <p>职责：加载 sig2 密钥分片（BIP32 根私钥），启动定时签名任务，
 * 对 BTC/BCH/LTC/DOGE/ETH/ERC20/TRON 等链的提现交易完成最终签名。
 *
 * <p>sig2 与 sig1 独立部署，各自持有不同的密钥分片，
 * 任一服务被攻破都无法单方面签名交易。
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "com.surprising.wallet.sig.second",
        "com.surprising.wallet.common"
})
public class WalletSig2Application {

    /**
     * 应用启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WalletSig2Application.class, args);
    }

    /**
     * 应用启动时初始化 sig2 的 BIP32 根密钥。
     * 密钥材料由 {@link WalletKeyMaterialProvider} 从数据库加载并提供。
     *
     * @param keyMaterial sig2 模式下的密钥材料提供者
     * @return 初始化完成后的空操作
     */
    @Bean
    ApplicationRunner initializeSig2Key(WalletKeyMaterialProvider keyMaterial) {
        return args -> BipNodeUtil.initialize(keyMaterial.sig2Root());
    }
}
