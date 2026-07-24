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

import java.security.MessageDigest;

/**
 * 摘要引擎抽象基类，继承JDK标准的{@link java.security.MessageDigest}并实现自定义
 * {@link Digest}接口。为Keccak等哈希算法提供统一的缓冲区管理、块处理流水线和
 * 状态复制能力。
 *
 * <h3>设计模式</h3>
 * 使用模板方法模式（Template Method），子类只需实现以下抽象方法：
 * <ul>
 *   <li>{@link #engineReset()}：重置哈希状态</li>
 *   <li>{@link #processBlock(byte[])}：处理一个完整的数据块（核心置换/压缩函数）</li>
 *   <li>{@link #doPadding(byte[], int)}：执行最终填充并输出摘要</li>
 *   <li>{@link #doInit()}：初始化内部状态（构造函数调用）</li>
 *   <li>{@link #engineGetDigestLength()}：返回摘要长度</li>
 *   <li>{@link #getBlockLength()}：返回块大小</li>
 * </ul>
 *
 * <h3>内部缓冲机制</h3>
 * <ul>
 *   <li>维护{@code inputBuf}（块大小）和{@code inputLen}（已缓冲字节数）</li>
 *   <li>{@link #update}方法累积数据，块满时自动调用{@link #processBlock}</li>
 *   <li>{@link #flush}返回尚未处理的字节数，供填充时使用</li>
 *   <li>支持{@link #copyState}实现摘要状态复制（用于HMAC等场景）</li>
 * </ul>
 *
 * @see KeccakCore Keccak算法的具体实现
 */
public abstract class DigestEngine extends MessageDigest implements Digest {

    private int digestLen;
    private final int blockLen;
    private int inputLen;
    private final byte[] inputBuf;
    private byte[] outputBuf;
    private long blockCount;

    /**
     * Instantiate the engine.
     */
    public DigestEngine(final String alg) {
        super(alg);
        this.doInit();
        this.digestLen = this.engineGetDigestLength();
        this.blockLen = this.getInternalBlockLength();
        this.inputBuf = new byte[this.blockLen];
        this.outputBuf = new byte[this.digestLen];
        this.inputLen = 0;
        this.blockCount = 0;
    }

    /**
     * Reset the hash algorithm state.
     */
    @Override
    protected abstract void engineReset();

    /**
     * Process one block of data.
     *
     * @param data the data block
     */
    protected abstract void processBlock(byte[] data);

    /**
     * Perform the final padding and store the result in the provided buffer. This method shall call
     * {@link #flush} and then {@link #update} with the appropriate padding data in order to getData
     * the full input data.
     *
     * @param buf the output buffer
     * @param off the output offset
     */
    protected abstract void doPadding(byte[] buf, int off);

    /**
     * This function is called at object creation time; the implementation should use it to perform
     * initialization tasks. After this method is called, the implementation should be ready to
     * process data or meaningfully honour calls such as {@link #engineGetDigestLength}
     */
    protected abstract void doInit();

    private void adjustDigestLen() {
        if (this.digestLen == 0) {
            this.digestLen = this.engineGetDigestLength();
            this.outputBuf = new byte[this.digestLen];
        }
    }

    /**
     * @see Digest
     */
    @Override
    public byte[] digest() {
        this.adjustDigestLen();
        final byte[] result = new byte[this.digestLen];
        this.digest(result, 0, this.digestLen);
        return result;
    }

    /**
     * @see Digest
     */
    @Override
    public byte[] digest(final byte[] input) {
        this.update(input, 0, input.length);
        return this.digest();
    }

    /**
     * @see Digest
     */
    @Override
    public int digest(final byte[] buf, final int offset, final int len) {
        this.adjustDigestLen();
        if (len >= this.digestLen) {
            this.doPadding(buf, offset);
            this.reset();
            return this.digestLen;
        } else {
            this.doPadding(this.outputBuf, 0);
            System.arraycopy(this.outputBuf, 0, buf, offset, len);
            this.reset();
            return len;
        }
    }

    /**
     * @see Digest
     */
    @Override
    public void reset() {
        this.engineReset();
        this.inputLen = 0;
        this.blockCount = 0;
    }

    /**
     * @see Digest
     */
    @Override
    public void update(final byte input) {
        this.inputBuf[this.inputLen++] = input;
        if (this.inputLen == this.blockLen) {
            this.processBlock(this.inputBuf);
            this.blockCount++;
            this.inputLen = 0;
        }
    }

    /**
     * @see Digest
     */
    @Override
    public void update(final byte[] input) {
        this.update(input, 0, input.length);
    }

    /**
     * @see Digest
     */
    @Override
    public void update(final byte[] input, int offset, int len) {
        while (len > 0) {
            int copyLen = this.blockLen - this.inputLen;
            if (copyLen > len) {
                copyLen = len;
            }
            System.arraycopy(input, offset, this.inputBuf, this.inputLen,
                    copyLen);
            offset += copyLen;
            this.inputLen += copyLen;
            len -= copyLen;
            if (this.inputLen == this.blockLen) {
                this.processBlock(this.inputBuf);
                this.blockCount++;
                this.inputLen = 0;
            }
        }
    }

    /**
     * Get the internal block length. This is the length (in bytes) of the array which will be passed
     * as parameter to {@link #processBlock}. The default implementation of this method calls {@link
     * #getBlockLength} and returns the same value. Overriding this method is useful when the
     * advertised block length (which is used, for instance, by HMAC) is suboptimal with regards to
     * internal buffering needs.
     *
     * @return the internal block length (in bytes)
     */
    protected int getInternalBlockLength() {
        return this.getBlockLength();
    }

    /**
     * Flush internal buffers, so that less than a block of data may at most be upheld.
     *
     * @return the number of bytes still unprocessed after the flush
     */
    protected final int flush() {
        return this.inputLen;
    }

    /**
     * Get a reference to an internal buffer with the same size than a block. The contents of that
     * buffer are defined only immediately after a call to {@link #flush()}: if {@link #flush()}
     * return the value {@code n}, then the first {@code n} bytes of the array returned by this method
     * are the {@code n} bytes of input data which are still unprocessed. The values of the remaining
     * bytes are undefined and may be altered at will.
     *
     * @return a block-sized internal buffer
     */
    protected final byte[] getBlockBuffer() {
        return this.inputBuf;
    }

    /**
     * Get the "block count": this is the number of times the {@link #processBlock} method has been
     * invoked for the current hash operation. That counter is incremented <em>after</em> the call to
     * {@link #processBlock}.
     *
     * @return the block count
     */
    protected long getBlockCount() {
        return this.blockCount;
    }

    /**
     * This function copies the internal buffering state to some other instance of a class extending
     * {@code DigestEngine}. It returns a reference to the copy. This method is intended to be called
     * by the implementation of the {@link #copy} method.
     *
     * @param dest the copy
     * @return the value {@code dest}
     */
    protected Digest copyState(final DigestEngine dest) {
        dest.inputLen = this.inputLen;
        dest.blockCount = this.blockCount;
        System.arraycopy(this.inputBuf, 0, dest.inputBuf, 0,
                this.inputBuf.length);
        this.adjustDigestLen();
        dest.adjustDigestLen();
        System.arraycopy(this.outputBuf, 0, dest.outputBuf, 0,
                this.outputBuf.length);
        return dest;
    }
}
