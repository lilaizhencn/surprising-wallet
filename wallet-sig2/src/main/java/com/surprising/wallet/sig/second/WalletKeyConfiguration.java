package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * sig2 模块的密钥配置。
 *
 * <p>从数据库加载 sig2 密钥分片（BIP32 根私钥），以 {@link WalletKeyMaterialProvider} 暴露给签名服务。
 * 模式固定为 {@link WalletKeyMaterialProvider.Mode#SIG2}，确保只加载 sig2 分片。
 */
@Configuration
public class WalletKeyConfiguration {

    /**
     * 创建密钥配置存储，从数据库读取密钥材料。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 密钥配置存储
     */
    @Bean
    WalletKeyConfigStore walletKeyConfigStore(JdbcTemplate jdbcTemplate) {
        return new WalletKeyConfigStore(jdbcTemplate);
    }

    /**
     * 创建 sig2 模式的密钥材料提供者。
     *
     * @param store 密钥配置存储
     * @return sig2 密钥材料提供者
     */
    @Bean
    WalletKeyMaterialProvider walletKeyMaterialProvider(WalletKeyConfigStore store) {
        return new WalletKeyMaterialProvider(store, WalletKeyMaterialProvider.Mode.SIG2);
    }
}
