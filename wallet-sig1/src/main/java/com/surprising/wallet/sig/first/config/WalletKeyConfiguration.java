package com.surprising.wallet.sig.first.config;

import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * sig1 模块的密钥配置。
 *
 * <p>从 Spring 配置加载 sig1 密钥分片（BIP32 根私钥），以 {@link WalletKeyMaterialProvider} 暴露给签名服务。
 * 模式固定为 {@link WalletKeyMaterialProvider.Mode#SIG1}，确保只加载 sig1 分片。
 */
@Configuration
public class WalletKeyConfiguration {

    /**
     * 创建 sig1 模式的密钥材料提供者。
     *
     * @return sig1 密钥材料提供者
     */
    @Bean
    WalletKeyMaterialProvider walletKeyMaterialProvider(
            @Value("${sw.wallet.keys.sig1-seed}") String sig1Seed,
            @Value("${sw.wallet.keys.sig2-seed}") String sig2Seed,
            @Value("${sw.wallet.keys.recovery-seed}") String recoverySeed,
            @Value("${sw.wallet.keys.ed25519-seed}") String ed25519Seed) {
        return new WalletKeyMaterialProvider(
                new WalletKeyConfig(sig1Seed, sig2Seed, recoverySeed, ed25519Seed),
                WalletKeyMaterialProvider.Mode.SIG1);
    }
}
