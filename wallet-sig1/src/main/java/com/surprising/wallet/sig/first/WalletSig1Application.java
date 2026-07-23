package com.surprising.wallet.sig.first;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author lilaizhencn
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.surprising"
        }
)
public class WalletSig1Application {

    public static void main(String[] args) {
        SpringApplication.run(WalletSig1Application.class, args);
    }

}

