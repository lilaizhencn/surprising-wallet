package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class WalletKeyConfiguration {
    @Bean
    WalletKeyConfigStore walletKeyConfigStore(JdbcTemplate jdbcTemplate) {
        return new WalletKeyConfigStore(jdbcTemplate);
    }

    @Bean
    WalletKeyMaterialProvider walletKeyMaterialProvider(WalletKeyConfigStore store) {
        return new WalletKeyMaterialProvider(store, WalletKeyMaterialProvider.Mode.SIG2);
    }
}
