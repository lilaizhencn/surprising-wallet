/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.wallet.util;

import java.math.BigInteger;

/**
 * 大整数（BigInteger）工具类，提供大整数的比较、运算和转换方法。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>比较运算</b>：{@link #isLessThan} / {@link #isMoreThan} / {@link #isEqual}
 *       / {@link #isNotEqual} / {@link #isPositive} / {@link #isZero}</li>
 *   <li><b>算术运算</b>：{@link #sum}大整数加法、{@link #max}取最大值、
 *       {@link #addSafely}安全int加法（溢出时返回Integer.MAX_VALUE）</li>
 *   <li><b>类型转换</b>：{@link #toBI(byte[])} / {@link #toBI(long)}
 *       将字节数组或长整数转为正大整数（使用符号位为1的构造函数）</li>
 *   <li><b>范围检查</b>：{@link #isNotCovers}判断一个数是否不足以覆盖另一个数</li>
 * </ul>
 *
 * <p>主要用于secp256k1椭圆曲线运算中的大整数比较（如签名验证中的r/s值范围检查）
 * 和TRON金额计算。</p>
 */
public class BIUtil {

    /**
     * @param valueA - not null
     * @param valueB - not null
     * @return true - if the valueA is less than valueB is zero
     */
    public static boolean isLessThan(final BigInteger valueA, final BigInteger valueB) {
        return valueA.compareTo(valueB) < 0;
    }

    /**
     * @param value - not null
     * @return true - if the param is zero
     */
    public static boolean isZero(final BigInteger value) {
        return value.compareTo(BigInteger.ZERO) == 0;
    }

    /**
     * @param valueA - not null
     * @param valueB - not null
     * @return true - if the valueA is equal to valueB is zero
     */
    public static boolean isEqual(final BigInteger valueA, final BigInteger valueB) {
        return valueA.compareTo(valueB) == 0;
    }

    /**
     * @param valueA - not null
     * @param valueB - not null
     * @return true - if the valueA is not equal to valueB is zero
     */
    public static boolean isNotEqual(final BigInteger valueA, final BigInteger valueB) {
        return !isEqual(valueA, valueB);
    }

    /**
     * @param valueA - not null
     * @param valueB - not null
     * @return true - if the valueA is more than valueB is zero
     */
    public static boolean isMoreThan(final BigInteger valueA, final BigInteger valueB) {
        return valueA.compareTo(valueB) > 0;
    }


    /**
     * @param valueA - not null
     * @param valueB - not null
     * @return sum - valueA + valueB
     */
    public static BigInteger sum(final BigInteger valueA, final BigInteger valueB) {
        return valueA.add(valueB);
    }


    /**
     * @param data = not null
     * @return new positive BigInteger
     */
    public static BigInteger toBI(final byte[] data) {
        return new BigInteger(1, data);
    }

    /**
     * @param data = not null
     * @return new positive BigInteger
     */
    public static BigInteger toBI(final long data) {
        return BigInteger.valueOf(data);
    }


    public static boolean isPositive(final BigInteger value) {
        return value.signum() > 0;
    }

    public static boolean isNotCovers(final BigInteger covers, final BigInteger value) {
        return covers.compareTo(value) < 0;
    }

    public static BigInteger max(final BigInteger first, final BigInteger second) {
        return first.compareTo(second) < 0 ? second : first;
    }

    /**
     * Returns a result of safe addition of two {@code int} values
     * {@code Integer.MAX_VALUE} is returned if overflow occurs
     */
    public static int addSafely(final int a, final int b) {
        final long res = (long) a + (long) b;
        return res > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) res;
    }
}
