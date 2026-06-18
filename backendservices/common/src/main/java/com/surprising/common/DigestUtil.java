package com.surprising.common;

import java.util.Random;

/**
 * @author lilaizhen
 * @date 2017/5/11
 */
public class DigestUtil {
    /**
     * 产生4位随机数(0000-9999)
     *
     * @return 4位随机数
     */
    public static String getFourRandom() {
        Random random = new Random();
        StringBuilder fourRandom = new StringBuilder(random.nextInt(10000) + "");
        int randLength = fourRandom.length();
        if (randLength < 4) {
            for (int i = 1; i <= 4 - randLength; i++) {
                fourRandom.insert(0, "0");
            }
        }
        return fourRandom.toString();
    }

    /**
     * 产生6位随机数(0000-9999)
     *
     * @return 6位随机数
     */
    public static String getSixRandom() {
        Random random = new Random();
        StringBuilder sixRandom = new StringBuilder(random.nextInt(1000000) + "");
        int randLength = sixRandom.length();
        if (randLength < 6) {
            for (int i = 1; i <= 6 - randLength; i++) {
                sixRandom.insert(0, "0");
            }
        }
        if (randLength > 6) {
            sixRandom = new StringBuilder(sixRandom.substring(0, 6));
        }
        return sixRandom.toString();
    }
}
