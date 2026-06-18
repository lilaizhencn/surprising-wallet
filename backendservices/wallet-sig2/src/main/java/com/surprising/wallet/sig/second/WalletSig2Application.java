package com.surprising.wallet.sig.second;

import com.surprising.wallet.signature.KeyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Properties;

/**
 * @author atomex
 * program arguments 添加--keyFile=/Users/atomexcn/Documents/code/atomex/key-atomex.conf
 */
@SpringBootApplication(scanBasePackages = {
        "com.surprising.wallet.sig.second",
        "com.surprising.wallet.common",
        "com.surprising.wallet.signature"
})
@Slf4j
public class WalletSig2Application {

    public static void main(String[] args) {
        SpringApplication.run(WalletSig2Application.class, args);
    }

    @Bean
    public ApplicationRunner applicationRunner() {

        return args -> {
            Properties properties = new Properties();
            properties.setProperty("masterNode", "tprv8ZgxMBicQKsPdMCWhEWHyiZCAu45dhRgupjpWBJ4vaYojzWURTemEm51Kf8tGQVcuerPJhCh98aCL9E81C2je6k3aeT7AJ5xELrVMprXf8U");
            KeyConfig.init(properties);
//            if (args.containsOption("keyFile")) {
//                List<String> optionValues = args.getOptionValues("keyFile");
//
//                String keyFilePath = optionValues.get(0);
//
//                File keyFile = new File(keyFilePath);
//
//                if (keyFile.isFile() && keyFile.exists()) {
//                    KeyConfig.init(keyFile.toURI().toURL());
//
//                    if (args.containsOption("delete")) {
//                        if (keyFile.delete()) {
//                            log.info("= = = = = = = = = = = Security Confirm= = = = = = = = = = =");
//                            log.info("*                                                         *");
//                            log.info("*                  Key File is Deleted                    *");
//                            log.info("*                                                         *");
//                            log.info("= = = = = = = = = = = = = = = = = = = = = = = = = = = = = =");
//                        }
//                    }
//                } else {
//                    log.error("= = = = = = = = = = = Security Confirm= = = = = = = = = = =");
//                    log.error("*                                                         *");
//                    log.error("*                  Can not find Key File                  *");
//                    log.error("*                                                         *");
//                    log.error("= = = = = = = = = = = = = = = = = = = = = = = = = = = = = =");
//                    throw new RuntimeException("Signature Server Missing Key File.");
//                }
//
//            } else {
//                log.error("= = = = = = = = = = = Security Confirm= = = = = = = = = = =");
//                log.error("*                                                         *");
//                log.error("*                  Can not find Key File                  *");
//                log.error("*                                                         *");
//                log.error("= = = = = = = = = = = = = = = = = = = = = = = = = = = = = =");
//                throw new RuntimeException("Signature Server Missing Key File.");
//            }
//
//
//                if (args.containsOption("secretFile")) {
//                    final List<String> optionValues = args.getOptionValues("secretFile");
//
//                    final String secretFilePath = optionValues.get(0);
//
//                    final File secretFile = new File(secretFilePath);
//
//                    if (secretFile.isFile() && secretFile.exists()) {
//                        SecretConfig.init(secretFile.toURI().toURL());
//
//                        if (args.containsOption("delete")) {
//                            if (secretFile.delete()) {
//                                log.info("= = = = = = = = = = = Security Confirm= = = = = = = = = = =");
//                                log.info("*                                                         *");
//                                log.info("*                  Secret File is Deleted                 *");
//                                log.info("*                                                         *");
//                                log.info("= = = = = = = = = = = = = = = = = = = = = = = = = = = = = =");
//                            }
//                        }
//                    }
//
//                } else {
//                    String key = "";
//                    final Scanner scanner = new Scanner(System.in);
//                    while (StringUtils.isEmpty(key)) {
//                        log.info("Please input secretKey:");
//                        key = scanner.nextLine();
//                        SecretConfig.init(key);
//                    }
//                }

        };
    }
}

