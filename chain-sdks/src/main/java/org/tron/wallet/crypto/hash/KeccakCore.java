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
 * Keccak哈希算法核心抽象类，实现Keccak海绵构造（sponge construction）的
 * 置换函数（permutation）和填充规则。
 *
 * <h3>Keccak算法原理</h3>
 * Keccak基于<b>海绵构造</b>，包含两个阶段：
 * <ol>
 *   <li><b>吸收阶段（Absorbing）</b>：将输入数据分块与内部状态（1600位，25个64位字）
 *       进行XOR运算，然后执行置换函数</li>
 *   <li><b>挤压阶段（Squeezing）</b>：从内部状态中提取输出摘要</li>
 * </ol>
 *
 * <h3>Keccak-f[1600]置换</h3>
 * 24轮迭代，每轮包含5个步骤（θ, ρ, π, χ, ι），对25个64位字（5x5矩阵）进行变换：
 * <ul>
 *   <li><b>θ（Theta）</b>：列奇偶扩散，每列奇偶位XOR到相邻列</li>
 *   <li><b>ρ（Rho）</b>：字内旋转，每个字按不同偏移量循环移位</li>
 *   <li><b>π（Pi）</b>：字重排列，将字按固定模式重新排列</li>
 *   <li><b>χ（Chi）</b>：行非线性变换，每行使用(~a & b) ^ c的非线性函数</li>
 *   <li><b>ι（Iota）</b>：轮常数XOR，每轮将不同的64位常数XOR到状态字[0,0]</li>
 * </ul>
 *
 * <h3>填充规则</h3>
 * 使用pad10*1填充：在消息末尾添加{@code 0x01}或{@code 0x81}以确保消息长度
 * 是块大小的整数倍，然后对最后几个状态字进行位翻转作为最终处理。
 *
 * <p>子类通过{@link #engineGetDigestLength()}决定输出长度（Keccak-256为32字节，
 * Keccak-512为64字节），块大小由此自动计算（200 - 2 * 摘要长度）。</p>
 */
abstract class KeccakCore extends DigestEngine {

    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L,
            0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L,
            0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L,
            0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL,
            0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L,
            0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L,
            0x0000000080000001L, 0x8000000080008008L
    };
    private long[] A;
    private byte[] tmpOut;

    KeccakCore(final String alg) {
        super(alg);
    }

    /**
     * Encode the 64-bit word {@code val} into the array {@code buf} at offset {@code off}, in
     * little-endian convention (least significant byte first).
     *
     * @param val the value to encode
     * @param buf the destination buffer
     * @param off the destination offset
     */
    private static void encodeLELong(final long val, final byte[] buf, final int off) {
        buf[off + 0] = (byte) val;
        buf[off + 1] = (byte) (val >>> 8);
        buf[off + 2] = (byte) (val >>> 16);
        buf[off + 3] = (byte) (val >>> 24);
        buf[off + 4] = (byte) (val >>> 32);
        buf[off + 5] = (byte) (val >>> 40);
        buf[off + 6] = (byte) (val >>> 48);
        buf[off + 7] = (byte) (val >>> 56);
    }

    /**
     * Decode a 64-bit little-endian word from the array {@code buf} at offset {@code off}.
     *
     * @param buf the source buffer
     * @param off the source offset
     * @return the decoded value
     */
    private static long decodeLELong(final byte[] buf, final int off) {
        return (buf[off + 0] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24)
                | ((buf[off + 4] & 0xFFL) << 32)
                | ((buf[off + 5] & 0xFFL) << 40)
                | ((buf[off + 6] & 0xFFL) << 48)
                | ((buf[off + 7] & 0xFFL) << 56);
    }

    @Override
    protected void engineReset() {
        this.doReset();
    }

    @Override
    protected void processBlock(final byte[] data) {
        /* Input block */
        for (int i = 0; i < data.length; i += 8) {
            this.A[i >>> 3] ^= decodeLELong(data, i);
        }

        long t0, t1, t2, t3, t4;
        long tt0, tt1, tt2, tt3, tt4;
        long t, kt;
        long c0, c1, c2, c3, c4, bnn;

        /*
         * Unrolling four rounds kills performance big time
         * on Intel x86 Core2, in both 32-bit and 64-bit modes
         * (less than 1 MB/s instead of 55 MB/s on x86-64).
         * Unrolling two rounds appears to be fine.
         */
        for (int j = 0; j < 24; j += 2) {

            tt0 = this.A[1] ^ this.A[6];
            tt1 = this.A[11] ^ this.A[16];
            tt0 ^= this.A[21] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[4] ^ this.A[9];
            tt3 = this.A[14] ^ this.A[19];
            tt0 ^= this.A[24];
            tt2 ^= tt3;
            t0 = tt0 ^ tt2;

            tt0 = this.A[2] ^ this.A[7];
            tt1 = this.A[12] ^ this.A[17];
            tt0 ^= this.A[22] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[0] ^ this.A[5];
            tt3 = this.A[10] ^ this.A[15];
            tt0 ^= this.A[20];
            tt2 ^= tt3;
            t1 = tt0 ^ tt2;

            tt0 = this.A[3] ^ this.A[8];
            tt1 = this.A[13] ^ this.A[18];
            tt0 ^= this.A[23] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[1] ^ this.A[6];
            tt3 = this.A[11] ^ this.A[16];
            tt0 ^= this.A[21];
            tt2 ^= tt3;
            t2 = tt0 ^ tt2;

            tt0 = this.A[4] ^ this.A[9];
            tt1 = this.A[14] ^ this.A[19];
            tt0 ^= this.A[24] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[2] ^ this.A[7];
            tt3 = this.A[12] ^ this.A[17];
            tt0 ^= this.A[22];
            tt2 ^= tt3;
            t3 = tt0 ^ tt2;

            tt0 = this.A[0] ^ this.A[5];
            tt1 = this.A[10] ^ this.A[15];
            tt0 ^= this.A[20] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[3] ^ this.A[8];
            tt3 = this.A[13] ^ this.A[18];
            tt0 ^= this.A[23];
            tt2 ^= tt3;
            t4 = tt0 ^ tt2;

            this.A[0] = this.A[0] ^ t0;
            this.A[5] = this.A[5] ^ t0;
            this.A[10] = this.A[10] ^ t0;
            this.A[15] = this.A[15] ^ t0;
            this.A[20] = this.A[20] ^ t0;
            this.A[1] = this.A[1] ^ t1;
            this.A[6] = this.A[6] ^ t1;
            this.A[11] = this.A[11] ^ t1;
            this.A[16] = this.A[16] ^ t1;
            this.A[21] = this.A[21] ^ t1;
            this.A[2] = this.A[2] ^ t2;
            this.A[7] = this.A[7] ^ t2;
            this.A[12] = this.A[12] ^ t2;
            this.A[17] = this.A[17] ^ t2;
            this.A[22] = this.A[22] ^ t2;
            this.A[3] = this.A[3] ^ t3;
            this.A[8] = this.A[8] ^ t3;
            this.A[13] = this.A[13] ^ t3;
            this.A[18] = this.A[18] ^ t3;
            this.A[23] = this.A[23] ^ t3;
            this.A[4] = this.A[4] ^ t4;
            this.A[9] = this.A[9] ^ t4;
            this.A[14] = this.A[14] ^ t4;
            this.A[19] = this.A[19] ^ t4;
            this.A[24] = this.A[24] ^ t4;
            this.A[5] = (this.A[5] << 36) | (this.A[5] >>> (64 - 36));
            this.A[10] = (this.A[10] << 3) | (this.A[10] >>> (64 - 3));
            this.A[15] = (this.A[15] << 41) | (this.A[15] >>> (64 - 41));
            this.A[20] = (this.A[20] << 18) | (this.A[20] >>> (64 - 18));
            this.A[1] = (this.A[1] << 1) | (this.A[1] >>> (64 - 1));
            this.A[6] = (this.A[6] << 44) | (this.A[6] >>> (64 - 44));
            this.A[11] = (this.A[11] << 10) | (this.A[11] >>> (64 - 10));
            this.A[16] = (this.A[16] << 45) | (this.A[16] >>> (64 - 45));
            this.A[21] = (this.A[21] << 2) | (this.A[21] >>> (64 - 2));
            this.A[2] = (this.A[2] << 62) | (this.A[2] >>> (64 - 62));
            this.A[7] = (this.A[7] << 6) | (this.A[7] >>> (64 - 6));
            this.A[12] = (this.A[12] << 43) | (this.A[12] >>> (64 - 43));
            this.A[17] = (this.A[17] << 15) | (this.A[17] >>> (64 - 15));
            this.A[22] = (this.A[22] << 61) | (this.A[22] >>> (64 - 61));
            this.A[3] = (this.A[3] << 28) | (this.A[3] >>> (64 - 28));
            this.A[8] = (this.A[8] << 55) | (this.A[8] >>> (64 - 55));
            this.A[13] = (this.A[13] << 25) | (this.A[13] >>> (64 - 25));
            this.A[18] = (this.A[18] << 21) | (this.A[18] >>> (64 - 21));
            this.A[23] = (this.A[23] << 56) | (this.A[23] >>> (64 - 56));
            this.A[4] = (this.A[4] << 27) | (this.A[4] >>> (64 - 27));
            this.A[9] = (this.A[9] << 20) | (this.A[9] >>> (64 - 20));
            this.A[14] = (this.A[14] << 39) | (this.A[14] >>> (64 - 39));
            this.A[19] = (this.A[19] << 8) | (this.A[19] >>> (64 - 8));
            this.A[24] = (this.A[24] << 14) | (this.A[24] >>> (64 - 14));
            bnn = ~this.A[12];
            kt = this.A[6] | this.A[12];
            c0 = this.A[0] ^ kt;
            kt = bnn | this.A[18];
            c1 = this.A[6] ^ kt;
            kt = this.A[18] & this.A[24];
            c2 = this.A[12] ^ kt;
            kt = this.A[24] | this.A[0];
            c3 = this.A[18] ^ kt;
            kt = this.A[0] & this.A[6];
            c4 = this.A[24] ^ kt;
            this.A[0] = c0;
            this.A[6] = c1;
            this.A[12] = c2;
            this.A[18] = c3;
            this.A[24] = c4;
            bnn = ~this.A[22];
            kt = this.A[9] | this.A[10];
            c0 = this.A[3] ^ kt;
            kt = this.A[10] & this.A[16];
            c1 = this.A[9] ^ kt;
            kt = this.A[16] | bnn;
            c2 = this.A[10] ^ kt;
            kt = this.A[22] | this.A[3];
            c3 = this.A[16] ^ kt;
            kt = this.A[3] & this.A[9];
            c4 = this.A[22] ^ kt;
            this.A[3] = c0;
            this.A[9] = c1;
            this.A[10] = c2;
            this.A[16] = c3;
            this.A[22] = c4;
            bnn = ~this.A[19];
            kt = this.A[7] | this.A[13];
            c0 = this.A[1] ^ kt;
            kt = this.A[13] & this.A[19];
            c1 = this.A[7] ^ kt;
            kt = bnn & this.A[20];
            c2 = this.A[13] ^ kt;
            kt = this.A[20] | this.A[1];
            c3 = bnn ^ kt;
            kt = this.A[1] & this.A[7];
            c4 = this.A[20] ^ kt;
            this.A[1] = c0;
            this.A[7] = c1;
            this.A[13] = c2;
            this.A[19] = c3;
            this.A[20] = c4;
            bnn = ~this.A[17];
            kt = this.A[5] & this.A[11];
            c0 = this.A[4] ^ kt;
            kt = this.A[11] | this.A[17];
            c1 = this.A[5] ^ kt;
            kt = bnn | this.A[23];
            c2 = this.A[11] ^ kt;
            kt = this.A[23] & this.A[4];
            c3 = bnn ^ kt;
            kt = this.A[4] | this.A[5];
            c4 = this.A[23] ^ kt;
            this.A[4] = c0;
            this.A[5] = c1;
            this.A[11] = c2;
            this.A[17] = c3;
            this.A[23] = c4;
            bnn = ~this.A[8];
            kt = bnn & this.A[14];
            c0 = this.A[2] ^ kt;
            kt = this.A[14] | this.A[15];
            c1 = bnn ^ kt;
            kt = this.A[15] & this.A[21];
            c2 = this.A[14] ^ kt;
            kt = this.A[21] | this.A[2];
            c3 = this.A[15] ^ kt;
            kt = this.A[2] & this.A[8];
            c4 = this.A[21] ^ kt;
            this.A[2] = c0;
            this.A[8] = c1;
            this.A[14] = c2;
            this.A[15] = c3;
            this.A[21] = c4;
            this.A[0] = this.A[0] ^ RC[j + 0];

            tt0 = this.A[6] ^ this.A[9];
            tt1 = this.A[7] ^ this.A[5];
            tt0 ^= this.A[8] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[24] ^ this.A[22];
            tt3 = this.A[20] ^ this.A[23];
            tt0 ^= this.A[21];
            tt2 ^= tt3;
            t0 = tt0 ^ tt2;

            tt0 = this.A[12] ^ this.A[10];
            tt1 = this.A[13] ^ this.A[11];
            tt0 ^= this.A[14] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[0] ^ this.A[3];
            tt3 = this.A[1] ^ this.A[4];
            tt0 ^= this.A[2];
            tt2 ^= tt3;
            t1 = tt0 ^ tt2;

            tt0 = this.A[18] ^ this.A[16];
            tt1 = this.A[19] ^ this.A[17];
            tt0 ^= this.A[15] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[6] ^ this.A[9];
            tt3 = this.A[7] ^ this.A[5];
            tt0 ^= this.A[8];
            tt2 ^= tt3;
            t2 = tt0 ^ tt2;

            tt0 = this.A[24] ^ this.A[22];
            tt1 = this.A[20] ^ this.A[23];
            tt0 ^= this.A[21] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[12] ^ this.A[10];
            tt3 = this.A[13] ^ this.A[11];
            tt0 ^= this.A[14];
            tt2 ^= tt3;
            t3 = tt0 ^ tt2;

            tt0 = this.A[0] ^ this.A[3];
            tt1 = this.A[1] ^ this.A[4];
            tt0 ^= this.A[2] ^ tt1;
            tt0 = (tt0 << 1) | (tt0 >>> 63);
            tt2 = this.A[18] ^ this.A[16];
            tt3 = this.A[19] ^ this.A[17];
            tt0 ^= this.A[15];
            tt2 ^= tt3;
            t4 = tt0 ^ tt2;

            this.A[0] = this.A[0] ^ t0;
            this.A[3] = this.A[3] ^ t0;
            this.A[1] = this.A[1] ^ t0;
            this.A[4] = this.A[4] ^ t0;
            this.A[2] = this.A[2] ^ t0;
            this.A[6] = this.A[6] ^ t1;
            this.A[9] = this.A[9] ^ t1;
            this.A[7] = this.A[7] ^ t1;
            this.A[5] = this.A[5] ^ t1;
            this.A[8] = this.A[8] ^ t1;
            this.A[12] = this.A[12] ^ t2;
            this.A[10] = this.A[10] ^ t2;
            this.A[13] = this.A[13] ^ t2;
            this.A[11] = this.A[11] ^ t2;
            this.A[14] = this.A[14] ^ t2;
            this.A[18] = this.A[18] ^ t3;
            this.A[16] = this.A[16] ^ t3;
            this.A[19] = this.A[19] ^ t3;
            this.A[17] = this.A[17] ^ t3;
            this.A[15] = this.A[15] ^ t3;
            this.A[24] = this.A[24] ^ t4;
            this.A[22] = this.A[22] ^ t4;
            this.A[20] = this.A[20] ^ t4;
            this.A[23] = this.A[23] ^ t4;
            this.A[21] = this.A[21] ^ t4;
            this.A[3] = (this.A[3] << 36) | (this.A[3] >>> (64 - 36));
            this.A[1] = (this.A[1] << 3) | (this.A[1] >>> (64 - 3));
            this.A[4] = (this.A[4] << 41) | (this.A[4] >>> (64 - 41));
            this.A[2] = (this.A[2] << 18) | (this.A[2] >>> (64 - 18));
            this.A[6] = (this.A[6] << 1) | (this.A[6] >>> (64 - 1));
            this.A[9] = (this.A[9] << 44) | (this.A[9] >>> (64 - 44));
            this.A[7] = (this.A[7] << 10) | (this.A[7] >>> (64 - 10));
            this.A[5] = (this.A[5] << 45) | (this.A[5] >>> (64 - 45));
            this.A[8] = (this.A[8] << 2) | (this.A[8] >>> (64 - 2));
            this.A[12] = (this.A[12] << 62) | (this.A[12] >>> (64 - 62));
            this.A[10] = (this.A[10] << 6) | (this.A[10] >>> (64 - 6));
            this.A[13] = (this.A[13] << 43) | (this.A[13] >>> (64 - 43));
            this.A[11] = (this.A[11] << 15) | (this.A[11] >>> (64 - 15));
            this.A[14] = (this.A[14] << 61) | (this.A[14] >>> (64 - 61));
            this.A[18] = (this.A[18] << 28) | (this.A[18] >>> (64 - 28));
            this.A[16] = (this.A[16] << 55) | (this.A[16] >>> (64 - 55));
            this.A[19] = (this.A[19] << 25) | (this.A[19] >>> (64 - 25));
            this.A[17] = (this.A[17] << 21) | (this.A[17] >>> (64 - 21));
            this.A[15] = (this.A[15] << 56) | (this.A[15] >>> (64 - 56));
            this.A[24] = (this.A[24] << 27) | (this.A[24] >>> (64 - 27));
            this.A[22] = (this.A[22] << 20) | (this.A[22] >>> (64 - 20));
            this.A[20] = (this.A[20] << 39) | (this.A[20] >>> (64 - 39));
            this.A[23] = (this.A[23] << 8) | (this.A[23] >>> (64 - 8));
            this.A[21] = (this.A[21] << 14) | (this.A[21] >>> (64 - 14));
            bnn = ~this.A[13];
            kt = this.A[9] | this.A[13];
            c0 = this.A[0] ^ kt;
            kt = bnn | this.A[17];
            c1 = this.A[9] ^ kt;
            kt = this.A[17] & this.A[21];
            c2 = this.A[13] ^ kt;
            kt = this.A[21] | this.A[0];
            c3 = this.A[17] ^ kt;
            kt = this.A[0] & this.A[9];
            c4 = this.A[21] ^ kt;
            this.A[0] = c0;
            this.A[9] = c1;
            this.A[13] = c2;
            this.A[17] = c3;
            this.A[21] = c4;
            bnn = ~this.A[14];
            kt = this.A[22] | this.A[1];
            c0 = this.A[18] ^ kt;
            kt = this.A[1] & this.A[5];
            c1 = this.A[22] ^ kt;
            kt = this.A[5] | bnn;
            c2 = this.A[1] ^ kt;
            kt = this.A[14] | this.A[18];
            c3 = this.A[5] ^ kt;
            kt = this.A[18] & this.A[22];
            c4 = this.A[14] ^ kt;
            this.A[18] = c0;
            this.A[22] = c1;
            this.A[1] = c2;
            this.A[5] = c3;
            this.A[14] = c4;
            bnn = ~this.A[23];
            kt = this.A[10] | this.A[19];
            c0 = this.A[6] ^ kt;
            kt = this.A[19] & this.A[23];
            c1 = this.A[10] ^ kt;
            kt = bnn & this.A[2];
            c2 = this.A[19] ^ kt;
            kt = this.A[2] | this.A[6];
            c3 = bnn ^ kt;
            kt = this.A[6] & this.A[10];
            c4 = this.A[2] ^ kt;
            this.A[6] = c0;
            this.A[10] = c1;
            this.A[19] = c2;
            this.A[23] = c3;
            this.A[2] = c4;
            bnn = ~this.A[11];
            kt = this.A[3] & this.A[7];
            c0 = this.A[24] ^ kt;
            kt = this.A[7] | this.A[11];
            c1 = this.A[3] ^ kt;
            kt = bnn | this.A[15];
            c2 = this.A[7] ^ kt;
            kt = this.A[15] & this.A[24];
            c3 = bnn ^ kt;
            kt = this.A[24] | this.A[3];
            c4 = this.A[15] ^ kt;
            this.A[24] = c0;
            this.A[3] = c1;
            this.A[7] = c2;
            this.A[11] = c3;
            this.A[15] = c4;
            bnn = ~this.A[16];
            kt = bnn & this.A[20];
            c0 = this.A[12] ^ kt;
            kt = this.A[20] | this.A[4];
            c1 = bnn ^ kt;
            kt = this.A[4] & this.A[8];
            c2 = this.A[20] ^ kt;
            kt = this.A[8] | this.A[12];
            c3 = this.A[4] ^ kt;
            kt = this.A[12] & this.A[16];
            c4 = this.A[8] ^ kt;
            this.A[12] = c0;
            this.A[16] = c1;
            this.A[20] = c2;
            this.A[4] = c3;
            this.A[8] = c4;
            this.A[0] = this.A[0] ^ RC[j + 1];
            t = this.A[5];
            this.A[5] = this.A[18];
            this.A[18] = this.A[11];
            this.A[11] = this.A[10];
            this.A[10] = this.A[6];
            this.A[6] = this.A[22];
            this.A[22] = this.A[20];
            this.A[20] = this.A[12];
            this.A[12] = this.A[19];
            this.A[19] = this.A[15];
            this.A[15] = this.A[24];
            this.A[24] = this.A[8];
            this.A[8] = t;
            t = this.A[1];
            this.A[1] = this.A[9];
            this.A[9] = this.A[14];
            this.A[14] = this.A[2];
            this.A[2] = this.A[13];
            this.A[13] = this.A[23];
            this.A[23] = this.A[4];
            this.A[4] = this.A[21];
            this.A[21] = this.A[16];
            this.A[16] = this.A[3];
            this.A[3] = this.A[17];
            this.A[17] = this.A[7];
            this.A[7] = t;
        }
    }

    @Override
    protected void doPadding(final byte[] out, final int off) {
        final int ptr = this.flush();
        final byte[] buf = this.getBlockBuffer();
        if ((ptr + 1) == buf.length) {
            buf[ptr] = (byte) 0x81;
        } else {
            buf[ptr] = (byte) 0x01;
            for (int i = ptr + 1; i < (buf.length - 1); i++) {
                buf[i] = 0;
            }
            buf[buf.length - 1] = (byte) 0x80;
        }
        this.processBlock(buf);
        this.A[1] = ~this.A[1];
        this.A[2] = ~this.A[2];
        this.A[8] = ~this.A[8];
        this.A[12] = ~this.A[12];
        this.A[17] = ~this.A[17];
        this.A[20] = ~this.A[20];
        final int dlen = this.engineGetDigestLength();
        for (int i = 0; i < dlen; i += 8) {
            encodeLELong(this.A[i >>> 3], this.tmpOut, i);
        }
        System.arraycopy(this.tmpOut, 0, out, off, dlen);
    }

    @Override
    protected void doInit() {
        this.A = new long[25];
        this.tmpOut = new byte[(this.engineGetDigestLength() + 7) & ~7];
        this.doReset();
    }

    @Override
    public int getBlockLength() {
        return 200 - 2 * this.engineGetDigestLength();
    }

    private void doReset() {
        for (int i = 0; i < 25; i++) {
            this.A[i] = 0;
        }
        this.A[1] = 0xFFFFFFFFFFFFFFFFL;
        this.A[2] = 0xFFFFFFFFFFFFFFFFL;
        this.A[8] = 0xFFFFFFFFFFFFFFFFL;
        this.A[12] = 0xFFFFFFFFFFFFFFFFL;
        this.A[17] = 0xFFFFFFFFFFFFFFFFL;
        this.A[20] = 0xFFFFFFFFFFFFFFFFL;
    }

    protected Digest copyState(final KeccakCore dst) {
        System.arraycopy(this.A, 0, dst.A, 0, 25);
        return super.copyState(dst);
    }

    @Override
    public String toString() {
        return "Keccak-" + (this.engineGetDigestLength() << 3);
    }
}
