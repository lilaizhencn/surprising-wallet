package com.surprising.wallet.common.utils;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * @author lilaizhen
 * @data 13/04/2018
 */

public class DoubleUtil {
    public DoubleUtil() {
    }

    public static String format(final double value, final int scale) {
        final String pattern = StringUtils.appendIfMissing("0.", StringUtils.repeat('0', scale), new CharSequence[0]);
        return (new DecimalFormat(pattern)).format(value);
    }

    public static double round(final double value, final int scale) {
        final int n = (int) Math.pow(10.0D, (double) scale);
        return DoubleUtil.divide(Math.floor(DoubleUtil.multiply(value, (double) n)), (double) n, scale);
    }

    public static double roundUp(final double value, final int scale) {
        final int n = (int) Math.pow(10.0D, (double) scale);
        final double result = DoubleUtil.divide(Math.ceil(DoubleUtil.multiply(value, (double) n)), (double) n, scale);
        return result;
    }

    public static double add(final double d1, final double... d2s) {
        BigDecimal bd1 = new BigDecimal(Double.toString(d1));
        final double[] var4 = d2s;
        final int var5 = d2s.length;

        for (int var6 = 0; var6 < var5; ++var6) {
            final double d2 = var4[var6];
            final BigDecimal bd2 = new BigDecimal(Double.toString(d2));
            bd1 = bd1.add(bd2);
        }

        return bd1.doubleValue();
    }

    public static double subtract(final double d1, final double d2) {
        final BigDecimal bd1 = new BigDecimal(Double.toString(d1));
        final BigDecimal bd2 = new BigDecimal(Double.toString(d2));
        return bd1.subtract(bd2).doubleValue();
    }

    public static double multiply(final double d1, final double d2) {
        final BigDecimal bd1 = new BigDecimal(Double.toString(d1));
        final BigDecimal bd2 = new BigDecimal(Double.toString(d2));
        return bd1.multiply(bd2).doubleValue();
    }

    public static double multiply(final double d1, final double... d2) {
        BigDecimal bd1 = new BigDecimal(Double.toString(d1));
        final double[] var4 = d2;
        final int var5 = d2.length;

        for (int var6 = 0; var6 < var5; ++var6) {
            final double d = var4[var6];
            bd1 = bd1.multiply(new BigDecimal(Double.toString(d)));
        }

        return bd1.doubleValue();
    }

    public static double divide(final double d1, final double d2, final int scale) {
        final BigDecimal bd1 = new BigDecimal(Double.toString(d1));
        final BigDecimal bd2 = new BigDecimal(Double.toString(d2));
        return bd1.divide(bd2, scale, 4).doubleValue();
    }
}
