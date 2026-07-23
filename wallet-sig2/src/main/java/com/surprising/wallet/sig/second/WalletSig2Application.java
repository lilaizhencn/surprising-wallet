package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {
        "com.surprising.wallet.sig.second",
        "com.surprising.wallet.common"
})
public class WalletSig2Application {
    public static void main(String[] args) {
        SpringApplication.run(WalletSig2Application.class, args);
    }

    @Bean
    ApplicationRunner initializeSig2Key(WalletKeyMaterialProvider keyMaterial) {
        return args -> BipNodeUtil.initialize(keyMaterial.sig2Root());
    }
}
