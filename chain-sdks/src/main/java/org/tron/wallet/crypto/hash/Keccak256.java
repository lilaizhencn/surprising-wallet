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
 * Keccak-256哈希算法实现，输出256位（32字节）摘要。
 *
 * <p>继承自{@link KeccakCore}，使用海绵构造（sponge construction）处理输入数据，
 * 比特率（rate）为1088位（136字节），容量（capacity）为512位。
 * 算法名称为{@code "tron-keccak-256"}，注册在TRON自定义的SpongyCastle安全提供者中。</p>
 *
 * <p>TRON和Ethereum使用原始Keccak-256而非NIST标准化后的SHA3-256，
 * 两者填充规则不同，因此相同输入会产生不同的哈希值。</p>
 *
 * @see KeccakCore Keccak算法核心实现
 * @see <a href="https://keccak.team/keccak_specs_summary.html">Keccak Specifications</a>
 */
public class Keccak256 extends KeccakCore {

    /**
     * Create the engine.
     */
    public Keccak256() {
        super("tron-keccak-256");
    }

    @Override
    public Digest copy() {
        return this.copyState(new Keccak256());
    }

    @Override
    public int engineGetDigestLength() {
        return 32;
    }

    @Override
    protected byte[] engineDigest() {
        return null;
    }

    @Override
    protected void engineUpdate(final byte arg0) {
    }

    @Override
    protected void engineUpdate(final byte[] arg0, final int arg1, final int arg2) {
    }
}
