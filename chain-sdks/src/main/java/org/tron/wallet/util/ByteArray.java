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

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;

/**
 * 字节数组工具类，提供字节数组与各种数据类型之间的转换方法。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>Hex编码/解码</b>：{@link #toHexString} / {@link #fromHexString}，
 *       支持{@code 0x}前缀，自动补全奇数长度</li>
 *   <li><b>数值转换</b>：{@link #toLong} / {@link #toInt}将字节数组解释为大端无符号整数</li>
 *   <li><b>字符串转换</b>：{@link #fromString} / {@link #toStr}进行UTF-8编码/解码</li>
 *   <li><b>原始类型封装</b>：{@link #fromLong} / {@link #fromInt}将long/int转为字节数组</li>
 *   <li><b>对象序列化</b>：{@link #fromObject}将可序列化对象转为字节数组</li>
 *   <li><b>子数组截取</b>：{@link #subArray}安全地截取指定范围的字节</li>
 * </ul>
 *
 * <p>用于TRON交易数据的序列化/反序列化，与{@link ByteUtil}配合使用。</p>
 */
@Slf4j
public class ByteArray {

    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    public static String toHexString(final byte[] data) {
        return data == null ? "" : Hex.toHexString(data);
    }

    /**
     * get bytes data from hex string data.
     */
    public static byte[] fromHexString(String data) {
        if (data == null) {
            return EMPTY_BYTE_ARRAY;
        }
        if (data.startsWith("0x")) {
            data = data.substring(2);
        }
        if (data.length() % 2 != 0) {
            data = "0" + data;
        }
        return Hex.decode(data);
    }

    /**
     * get long data from bytes data.
     */
    public static long toLong(final byte[] b) {
        return ArrayUtils.isEmpty(b) ? 0 : new BigInteger(1, b).longValue();
    }

    /**
     * get int data from bytes data.
     */
    public static int toInt(final byte[] b) {
        return ArrayUtils.isEmpty(b) ? 0 : new BigInteger(1, b).intValue();
    }

    /**
     * get bytes data from string data.
     */
    public static byte[] fromString(final String s) {
        return StringUtils.isBlank(s) ? null : s.getBytes();
    }

    /**
     * get string data from bytes data.
     */
    public static String toStr(final byte[] b) {
        return ArrayUtils.isEmpty(b) ? null : new String(b);
    }

    public static byte[] fromLong(final long val) {
        return Longs.toByteArray(val);
    }

    public static byte[] fromInt(final int val) {
        return Ints.toByteArray(val);
    }

    /**
     * get bytes data from object data.
     */
    public static byte[] fromObject(final Object obj) {
        byte[] bytes = null;
        try (final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             final ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(obj);
            objectOutputStream.flush();
            bytes = byteArrayOutputStream.toByteArray();
        } catch (final IOException e) {
            log.error("objectToByteArray failed: " + e.getMessage(), e);
        }
        return bytes;
    }

    /**
     * Generate a subarray of a given byte array.
     *
     * @param input the input byte array
     * @param start the start index
     * @param end   the end index
     * @return a subarray of <tt>input</tt>, ranging from <tt>start</tt> (inclusively) to <tt>end</tt>
     * (exclusively)
     */
    public static byte[] subArray(final byte[] input, final int start, final int end) {
        final byte[] result = new byte[end - start];
        System.arraycopy(input, start, result, 0, end - start);
        return result;
    }
}
