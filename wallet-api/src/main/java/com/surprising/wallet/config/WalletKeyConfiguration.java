package com.surprising.wallet.config;

import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * wallet-api 应用的密钥 Spring 配置。
 *
 * <p>从 Spring 配置加载四个根 Seed，并创建 WALLET_SERVER 模式的
 * {@link WalletKeyMaterialProvider}，供地址派生、签名等业务使用。
 *
 * @see com.surprising.wallet.common.key.WalletKeyMaterialProvider
 */
@Configuration
public
class WalletKeyConfiguration {

    /**
     * 创建 WALLET_SERVER 模式的密钥材料提供者。
     *
     * @return 密钥材料提供者
     */
    @Bean
    WalletKeyMaterialProvider walletKeyMaterialProvider(
            @Value("${sw.wallet.keys.sig1-seed}") String sig1Seed,
            @Value("${sw.wallet.keys.sig2-seed}") String sig2Seed,
            @Value("${sw.wallet.keys.recovery-seed}") String recoverySeed,
            @Value("${sw.wallet.keys.ed25519-seed}") String ed25519Seed) {
        return new WalletKeyMaterialProvider(
                new WalletKeyConfig(sig1Seed, sig2Seed, recoverySeed, ed25519Seed),
                WalletKeyMaterialProvider.Mode.WALLET_SERVER);
    }
}
