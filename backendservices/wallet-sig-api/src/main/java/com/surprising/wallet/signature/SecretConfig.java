package com.surprising.wallet.signature;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;

import java.net.URL;

/**
 * @author lilaizhen
 **/
@Log4j2
public class SecretConfig {

    private static Config conf;

    private static String aes = null;

    public static void init(URL url) {
        SecretConfig.conf = ConfigFactory.load(ConfigFactory.parseURL(url));
    }

    public static void init(String key) {
        if (StringUtils.isEmpty(key)) {
            return;
        }
        SecretConfig.aes = key;
    }

    private static String getValue(String key) {
        if (SecretConfig.aes != null) {
            return SecretConfig.aes;
        }

        if (SecretConfig.conf != null && SecretConfig.conf.hasPath(key)) {
            return SecretConfig.conf.getString(key);
        }
        return null;
    }

    public static String decryptKey(String data) {
        String aesKey = SecretConfig.getValue("secret");
        if (StringUtils.isEmpty(aesKey)) {
            return null;
        }
        return AES.decrypt(data, aesKey);
    }
}
