package com.surprising.wallet.common.utils;

import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author lilaizhen
 * @data 29/03/2018
 */
//@Slf4j
public class PropertyUtil {
    private static Properties props;

    static {
        PropertyUtil.loadProps();
    }

    static private void loadProps() {
//        log.info("loading properties");
        PropertyUtil.props = new Properties();
        InputStream in = null;
        try {
            in = PropertyUtil.class.getClassLoader().getResourceAsStream("application.properties");

            PropertyUtil.props.load(in);
        } catch (FileNotFoundException e) {
//            log.error("application.properties not found");
        } catch (IOException e) {
//            log.error("IOException");
        } finally {
            try {
                if (null != in) {
                    in.close();
                }
            } catch (IOException e) {
//                log.error("application.properties IOException");
            }
        }
//        log.info("load application.properties complete");
    }


    public static String getProperty(String key) {
        if (null == PropertyUtil.props) {
            PropertyUtil.loadProps();
        }
        return PropertyUtil.props.getProperty(key);
    }

    public static String getProperty(String key, String defaultValue) {
        if (null == PropertyUtil.props) {
            PropertyUtil.loadProps();
        }
        return PropertyUtil.props.getProperty(key, defaultValue);
    }
}
