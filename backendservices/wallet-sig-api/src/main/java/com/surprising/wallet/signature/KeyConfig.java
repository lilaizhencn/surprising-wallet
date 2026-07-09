package com.surprising.wallet.signature;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.extern.log4j.Log4j2;

import java.net.URL;
import java.util.Properties;

/**
 * @author lilaizhen
 * @create 2018-11-27 下午2:13
 **/
@Log4j2
public class KeyConfig {

    private static Config conf;
    private SecretConfig secretConfig;

    public static void init(URL url) {
        KeyConfig.conf = ConfigFactory.load(ConfigFactory.parseURL(url));
    }

    public static void init(Properties properties) {
        KeyConfig.conf = ConfigFactory.parseProperties(properties);
    }

    public static String getValue(String key) {
        if (KeyConfig.conf != null && KeyConfig.conf.hasPath(key)) {
            return KeyConfig.conf.getString(key);
        }
        return null;
    }
}
