package com.surprising.wallet.config;

import com.surprising.wallet.common.security.DatabaseRoleIsolation;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class WalletApprovalDatabaseIsolation {
    @Bean ApplicationRunner walletApprovalDatabaseIsolationCheck(DataSource source) {
        return arguments->DatabaseRoleIsolation.wallet(source);
    }
}
