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

package org.tron.wallet.crypto;

import lombok.extern.slf4j.Slf4j;
import org.tron.wallet.constants.Constant;
import org.tron.wallet.crypto.jce.TronCastleProvider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;

import static java.util.Arrays.copyOfRange;

/**
 * TRON哈希工具类，提供基于<b>Keccak-256</b>和<b>Keccak-512</b>算法的哈希计算功能。
 *
 * <h3>Keccak算法说明</h3>
 * Keccak是SHA-3标准的底层算法，TRON（继承自Ethereum）使用原始的Keccak-256而非
 * NIST标准化的SHA3-256（两者在填充规则上略有不同）。Keccak基于海绵构造（sponge
 * construction），具有以下特点：
 * <ul>
 *   <li>输出长度可变：Keccak-256输出32字节，Keccak-512输出64字节</li>
 *   <li>抗碰撞性强：256位输出提供128位经典安全性</li>
 *   <li>基于自定义TRON-KECCAK-{256,512}算法，通过SpongyCastle安全提供者注册</li>
 * </ul>
 *
 * <h3>TRON地址计算</h3>
 * TRON地址通过{@link #sha3omit12}计算：取Keccak-256哈希的第12到第31字节（共20字节），
 * 并在开头添加主网地址前缀字节{@code 0x41}，生成21字节地址。
 *
 * <h3>方法概览</h3>
 * <ul>
 *   <li>{@link #sha3(byte[])}：单输入Keccak-256哈希</li>
 *   <li>{@link #sha3(byte[], byte[])}：双输入拼接后Keccak-256哈希</li>
 *   <li>{@link #sha3(byte[], int, int)}：指定范围的Keccak-256哈希</li>
 *   <li>{@link #sha512(byte[])}：Keccak-512哈希</li>
 *   <li>{@link #sha3omit12(byte[])}：TRON地址专用哈希（取末20字节+前缀）</li>
 * </ul>
 */
@Slf4j
public class Hash {

    private static final Provider CRYPTO_PROVIDER;

    private static final String HASH_256_ALGORITHM_NAME;
    private static final String HASH_512_ALGORITHM_NAME;

    private static final byte addressPreFixByte = Constant.ADD_PRE_FIX_BYTE_MAINNET;

    static {
        Security.addProvider(TronCastleProvider.getInstance());
        CRYPTO_PROVIDER = Security.getProvider("SC");
        HASH_256_ALGORITHM_NAME = "TRON-KECCAK-256";
        HASH_512_ALGORITHM_NAME = "TRON-KECCAK-512";
    }

    public static byte[] sha3(final byte[] input) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_256_ALGORITHM_NAME,
                    CRYPTO_PROVIDER);
            digest.update(input);
            return digest.digest();
        } catch (final NoSuchAlgorithmException e) {
            log.error("Can't find such algorithm", e);
            throw new RuntimeException(e);
        }

    }

    public static byte[] sha3(final byte[] input1, final byte[] input2) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_256_ALGORITHM_NAME,
                    CRYPTO_PROVIDER);
            digest.update(input1, 0, input1.length);
            digest.update(input2, 0, input2.length);
            return digest.digest();
        } catch (final NoSuchAlgorithmException e) {
            log.error("Can't find such algorithm", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * hashing chunk of the data
     *
     * @param input  - data for hash
     * @param start  - start of hashing chunk
     * @param length - length of hashing chunk
     * @return - keccak hash of the chunk
     */
    public static byte[] sha3(final byte[] input, final int start, final int length) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_256_ALGORITHM_NAME,
                    CRYPTO_PROVIDER);
            digest.update(input, start, length);
            return digest.digest();
        } catch (final NoSuchAlgorithmException e) {
            log.error("Can't find such algorithm", e);
            throw new RuntimeException(e);
        }
    }

    public static byte[] sha512(final byte[] input) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_512_ALGORITHM_NAME,
                    CRYPTO_PROVIDER);
            digest.update(input);
            return digest.digest();
        } catch (final NoSuchAlgorithmException e) {
            log.error("Can't find such algorithm", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Calculates RIGTMOST160(SHA3(input)). This is used in address calculations. *
     *
     * @param input - data
     * @return - add_pre_fix + 20 right bytes of the hash keccak of the data
     */
    public static byte[] sha3omit12(final byte[] input) {
        final byte[] hash = sha3(input);
        final byte[] address = copyOfRange(hash, 11, hash.length);
        address[0] = Constant.ADD_PRE_FIX_BYTE_MAINNET;
        return address;
    }
}
