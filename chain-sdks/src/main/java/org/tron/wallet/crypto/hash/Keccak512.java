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

package org.tron.wallet.crypto.hash;

/**
 * Keccak-512哈希算法实现，输出512位（64字节）摘要。
 *
 * <p>继承自{@link KeccakCore}，使用海绵构造（sponge construction）处理输入数据，
 * 比特率（rate）为576位（72字节），容量（capacity）为1024位。
 * 算法名称为{@code "tron-keccak-512"}，注册在TRON自定义的SpongyCastle安全提供者中。</p>
 *
 * <p>主要用途：</p>
 * <ul>
 *   <li>TRON交易签名消息的哈希</li>
 *   <li>需要更长摘要的安全场景</li>
 * </ul>
 *
 * @see KeccakCore Keccak算法核心实现
 */
public class Keccak512 extends KeccakCore {

    /**
     * Create the engine.
     */
    public Keccak512() {
        super("tron-keccak-512");
    }

    @Override
    public Digest copy() {
        return this.copyState(new Keccak512());
    }

    @Override
    public int engineGetDigestLength() {
        return 64;
    }

    @Override
    protected byte[] engineDigest() {
        return null;
    }

    @Override
    protected void engineUpdate(final byte input) {
    }

    @Override
    protected void engineUpdate(final byte[] input, final int offset, final int len) {
    }
}
