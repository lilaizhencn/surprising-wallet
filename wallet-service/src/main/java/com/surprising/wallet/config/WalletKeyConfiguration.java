package com.surprising.wallet.config;

import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * wallet-server 模块的密钥 Spring 配置。
 *
 * <p>创建 {@link WalletKeyConfigStore}（从数据库加载密钥材料）和
 * {@link WalletKeyMaterialProvider}（模式为 WALLET_SERVER），
 * 供地址派生、签名等业务使用。
 *
 * @see com.surprising.wallet.common.key.WalletKeyConfigStore
 * @see com.surprising.wallet.common.key.WalletKeyMaterialProvider
 */
@Configuration
public
class WalletKeyConfiguration {

    /**
     * 创建密钥配置存储，从数据库读取 BIP32 密钥材料。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 密钥配置存储
     */
    @Bean
    WalletKeyConfigStore walletKeyConfigStore(JdbcTemplate jdbcTemplate) {
        return new WalletKeyConfigStore(jdbcTemplate);
    }

    /**
     * 创建 WALLET_SERVER 模式的密钥材料提供者。
     *
     * @param store 密钥配置存储
     * @return 密钥材料提供者
     */
    @Bean
    WalletKeyMaterialProvider walletKeyMaterialProvider(WalletKeyConfigStore store) {
        return new WalletKeyMaterialProvider(store, WalletKeyMaterialProvider.Mode.WALLET_SERVER);
    }
}
