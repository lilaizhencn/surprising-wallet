/**
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 * Copyright 2015 J. Ross Nicoll
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import org.libdohj.core.AuxPoWNetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;


/**
 * <p>An AuxPoW header wraps a block header from another coin, enabling the foreign
 * chain's proof of work to be used for this chain as well. <b>Note: </b>
 * NetworkParameters for AuxPoW networks <b>must</b> implement AltcoinNetworkParameters
 * in order for AuxPoW to work.</p>
 */
public class AuxPoW extends ChildMessage {

    public static final byte[] MERGED_MINING_HEADER = new byte[]{
            (byte) 0xfa, (byte) 0xbe, "m".getBytes()[0], "m".getBytes()[0]
    };

    /**
     * Maximum index of the merkle root hash in the coinbase transaction script,
     * where no merged mining header is present.
     */
    protected static final int MAX_INDEX_PC_BACKWARDS_COMPATIBILITY = 20;

    private static final Logger log = LoggerFactory.getLogger(AuxPoW.class);
    private static final long serialVersionUID = -8567546957352643140L;

    private Transaction transaction;
    private Sha256Hash hashBlock;
    private MerkleBranch coinbaseBranch;
    private MerkleBranch chainMerkleBranch;
    private AltcoinBlock parentBlockHeader;

    // Transactions can be encoded in a way that will use more bytes than is optimal
    // (due to VarInts having multiple encodings)
    // MAX_BLOCK_SIZE must be compared to the optimal encoding, not the actual encoding, so when parsing, we keep track
    // of the size of the ideal encoding in addition to the actual message size (which Message needs) so that Blocks
    // can properly keep track of optimal encoded size
    private transient int optimalEncodingMessageSize;

    public AuxPoW(final NetworkParameters params, @Nullable final Message parent) {
        super(params);
        this.transaction = new Transaction(params);
        this.hashBlock = Sha256Hash.ZERO_HASH;
        this.coinbaseBranch = new MerkleBranch(params, this);
        this.chainMerkleBranch = new MerkleBranch(params, this);
        this.parentBlockHeader = null;
    }

    /**
     * Creates an AuxPoW header by reading payload starting from offset bytes in. Length of header is fixed.
     *
     * @param params     NetworkParameters object.
     * @param payload    Bitcoin protocol formatted byte array containing message content.
     * @param offset     The location of the first payload byte within the array.
     * @param parent     The message element which contains this header.
     * @param serializer the serializer to use for this message.
     * @throws ProtocolException
     */
    public AuxPoW(final NetworkParameters params, final byte[] payload, final int offset, final Message parent, final MessageSerializer serializer)
            throws ProtocolException {
        super(params, payload, offset, parent, serializer, Message.UNKNOWN_LENGTH);
    }

    /**
     * Creates an AuxPoW header by reading payload starting from offset bytes in. Length of header is fixed.
     *
     * @param params     NetworkParameters object.
     * @param payload    Bitcoin protocol formatted byte array containing message content.
     * @param parent     The message element which contains this header.
     * @param serializer the serializer to use for this message.
     */
    public AuxPoW(final NetworkParameters params, final byte[] payload, @Nullable final Message parent, final MessageSerializer serializer)
            throws ProtocolException {
        super(params, payload, 0, parent, serializer, Message.UNKNOWN_LENGTH);
    }

    protected static int calcLength(final byte[] buf, final int offset) {
        VarInt varint;
        // jump past transaction
        int cursor = offset + Transaction.calcLength(buf, offset);

        // jump past header hash
        cursor += 4;

        // Coin base branch
        cursor += MerkleBranch.calcLength(buf, offset);

        // Block chain branch
        cursor += MerkleBranch.calcLength(buf, offset);

        // Block header
        cursor += Block.HEADER_SIZE;

        return cursor - offset + 4;
    }

    @Override
    protected void parse() throws ProtocolException {
        this.cursor = this.offset;
        this.transaction = new Transaction(this.params, this.payload, this.cursor, this, this.serializer, Message.UNKNOWN_LENGTH,null);
        this.cursor += this.transaction.getOptimalEncodingMessageSize();
        this.optimalEncodingMessageSize = this.transaction.getOptimalEncodingMessageSize();

        this.hashBlock = this.readHash();
        this.optimalEncodingMessageSize += 32; // Add the hash size to the optimal encoding

        this.coinbaseBranch = new MerkleBranch(this.params, this, this.payload, this.cursor, this.serializer);
        this.cursor += this.coinbaseBranch.getOptimalEncodingMessageSize();
        this.optimalEncodingMessageSize += this.coinbaseBranch.getOptimalEncodingMessageSize();

        this.chainMerkleBranch = new MerkleBranch(this.params, this, this.payload, this.cursor, this.serializer);
        this.cursor += this.chainMerkleBranch.getOptimalEncodingMessageSize();
        this.optimalEncodingMessageSize += this.chainMerkleBranch.getOptimalEncodingMessageSize();

        // Make a copy of JUST the contained block header, so the block parser doesn't try reading
        // transactions past the end
        final byte[] blockBytes = Arrays.copyOfRange(this.payload, this.cursor, this.cursor + Block.HEADER_SIZE);
        this.cursor += Block.HEADER_SIZE;
        this.parentBlockHeader = new AltcoinBlock(this.params, blockBytes, 0, this, this.serializer, Block.HEADER_SIZE);

        this.length = this.cursor - this.offset;
    }

    public int getOptimalEncodingMessageSize() {
        if (this.optimalEncodingMessageSize != 0) {
            return this.optimalEncodingMessageSize;
        }
        this.optimalEncodingMessageSize = this.getMessageSize();
        return this.optimalEncodingMessageSize;
    }

    @Override
    public String toString() {
        return this.toString(null);
    }

    /**
     * A human readable version of the transaction useful for debugging. The format is not guaranteed to be stable.
     *
     * @param chain If provided, will be used to estimate lock times (if set). Can be null.
     */
    public String toString(@Nullable final AbstractBlockChain chain) {
        return this.transaction.toString(chain,null);
    }

    @Override
    protected void bitcoinSerializeToStream(final OutputStream stream) throws IOException {
        this.transaction.bitcoinSerialize(stream);
        stream.write(Utils.reverseBytes(this.hashBlock.getBytes()));

        this.coinbaseBranch.bitcoinSerialize(stream);
        this.chainMerkleBranch.bitcoinSerialize(stream);

        this.parentBlockHeader.bitcoinSerializeToStream(stream);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        final AuxPoW input = (AuxPoW) o;
        if (!this.transaction.equals(input.transaction)) {
            return false;
        }
        if (!this.hashBlock.equals(input.hashBlock)) {
            return false;
        }
        if (!this.coinbaseBranch.equals(input.coinbaseBranch)) {
            return false;
        }
        if (!this.chainMerkleBranch.equals(input.chainMerkleBranch)) {
            return false;
        }
        if (!this.parentBlockHeader.equals(input.parentBlockHeader)) {
            return false;
        }
        return this.getHash().equals(input.getHash());
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + this.transaction.hashCode();
        result = 31 * result + this.hashBlock.hashCode();
        result = 31 * result + this.coinbaseBranch.hashCode();
        result = 31 * result + this.chainMerkleBranch.hashCode();
        result = 31 * result + this.parentBlockHeader.hashCode();
        return result;
    }

    /**
     * Get the block header from the parent blockchain. The hash of the header
     * is the value which should match the difficulty target. Note that blocks are
     * not necessarily part of the parent blockchain, they simply must be valid
     * blocks at the difficulty of the child blockchain.
     */
    public AltcoinBlock getParentBlockHeader() {
        return this.parentBlockHeader;
    }

    /**
     * Get the coinbase transaction from the AuxPoW header. This should contain a
     * reference back to the block hash in its input scripts, to prove that the
     * transaction was created after the block.
     */
    public Transaction getCoinbase() {
        return this.transaction;
    }

    /**
     * Get the Merkle branch used to connect the AuXPow header with this blockchain.
     */
    public MerkleBranch getChainMerkleBranch() {
        return this.chainMerkleBranch;
    }

    /**
     * Get the Merkle branch used to connect the coinbase transaction with this blockchain.
     */
    public MerkleBranch getCoinbaseBranch() {
        return this.coinbaseBranch;
    }

    /**
     * Check the proof of work for this AuxPoW header meets the target
     * difficulty.
     *
     * @param hashAuxBlock hash of the block the AuxPoW header is attached to.
     * @param target       the difficulty target after decoding from compact bits.
     */
    protected boolean checkProofOfWork(final Sha256Hash hashAuxBlock,
                                       final BigInteger target, final boolean throwException) throws VerificationException {
        if (!(this.params instanceof AuxPoWNetworkParameters)) {
            if (throwException) {
                // Should be impossible
                throw new VerificationException("Network parameters are not an instance of AuxPoWNetworkParameters, AuxPoW support is not available.");
            }
            return false;
        }
        final AuxPoWNetworkParameters altcoinParams = (AuxPoWNetworkParameters) this.params;

        if (0 != this.getCoinbaseBranch().getIndex()) {
            if (throwException) {
                // I don't like the message, but it correlates with what's in the reference client.
                throw new VerificationException("AuxPow is not a generate");
            }
            return false;
        }

        if (!altcoinParams.isTestNet()
                && this.parentBlockHeader.getChainID() == altcoinParams.getChainID()) {
            if (throwException) {
                throw new VerificationException("Aux POW parent has our chain ID");
            }
            return false;
        }

        if (this.getChainMerkleBranch().size() > 30) {
            if (throwException) {
                throw new VerificationException("Aux POW chain merkle branch too long");
            }
            return false;
        }

        final Sha256Hash nRootHash = this.getChainMerkleBranch().calculateMerkleRoot(hashAuxBlock);
        final byte[] vchRootHash = nRootHash.getBytes();

        // Check that the coinbase transaction is in the merkle tree of the
        // parent block header
        if (!this.getCoinbaseBranch().calculateMerkleRoot(this.getCoinbase().getHash()).equals(this.parentBlockHeader.getMerkleRoot())) {
            if (throwException) {
                throw new VerificationException("Aux POW merkle root incorrect");
            }
            return false;
        }

        if (this.getCoinbase().getInputs().isEmpty()) {
            throw new VerificationException("Coinbase transaction has no inputs");
        }

        // Check that the chain merkle root is in the coinbase
        final byte[] script = this.getCoinbase().getInput(0).getScriptBytes();

        // Check that the same work is not submitted twice to our chain, by
        // confirming that the child block hash is in the coinbase merkle tree
        int pcHead = -1;
        int pc = -1;

        for (int scriptIdx = 0; scriptIdx < script.length; scriptIdx++) {
            if (arrayMatch(script, scriptIdx, MERGED_MINING_HEADER)) {
                // Enforce only one chain merkle root by checking that a single instance of the merged
                // mining header exists just before.
                if (pcHead >= 0) {
                    if (throwException) {
                        throw new VerificationException("Multiple merged mining headers in coinbase");
                    }
                    return false;
                }
                pcHead = scriptIdx;
            } else if (arrayMatch(script, scriptIdx, vchRootHash)) {
                pc = scriptIdx;
            }
        }

        if (pc == -1) {
            if (throwException) {
                throw new VerificationException("Aux POW missing chain merkle root in parent coinbase");
            }
            return false;
        }

        if (pcHead != -1) {
            if (pcHead + MERGED_MINING_HEADER.length != pc) {
                if (throwException) {
                    throw new VerificationException("Merged mining header is not just before chain merkle root");
                }
                return false;
            }
        } else {
            // For backward compatibility.
            // Enforce only one chain merkle root by checking that it starts early in the coinbase.
            // 8-12 bytes are enough to encode extraNonce and nBits.
            if (pc > MAX_INDEX_PC_BACKWARDS_COMPATIBILITY) {
                if (throwException) {
                    throw new VerificationException("Aux POW chain merkle root must start in the first 20 bytes of the parent coinbase");
                }
                return false;
            }
        }

        // Ensure we are at a deterministic point in the merkle leaves by hashing
        // a nonce and our chain ID and comparing to the index.
        pc += vchRootHash.length;
        if ((script.length - pc) < 8) {
            if (throwException) {
                throw new VerificationException("Aux POW missing chain merkle tree size and nonce in parent coinbase");
            }
            return false;
        }

        final byte[] sizeBytes = Utils.reverseBytes(Arrays.copyOfRange(script, pc, pc + 4));
        final int branchSize = ByteBuffer.wrap(sizeBytes).getInt();
        if (branchSize != (1 << this.getChainMerkleBranch().size())) {
            if (throwException) {
                throw new VerificationException("Aux POW merkle branch size does not match parent coinbase");
            }
            return false;
        }

        final long nonce = getNonceFromScript(script, pc);

        if (this.getChainMerkleBranch().getIndex() != getExpectedIndex(nonce, ((AuxPoWNetworkParameters) this.params).getChainID(), this.getChainMerkleBranch().size())) {
            if (throwException) {
                throw new VerificationException("Aux POW wrong index in chain merkle branch for chain ID "
                        + ((AuxPoWNetworkParameters) this.params).getChainID() + ". Was "
                        + this.getChainMerkleBranch().getIndex() + ", expected "
                        + getExpectedIndex(nonce, ((AuxPoWNetworkParameters) this.params).getChainID(), this.getChainMerkleBranch().size()));
            }
            return false;
        }

        final Sha256Hash hash = altcoinParams.getBlockDifficultyHash(this.getParentBlockHeader());
        final BigInteger hashVal = hash.toBigInteger();
        if (hashVal.compareTo(target) > 0) {
            // Proof of work check failed!
            if (throwException) {
                throw new VerificationException("Hash is higher than target: " + hash.toString() + " vs "
                        + target.toString(16));
            }
            return false;
        }

        return true;
    }

    /**
     * Get the nonce value from the coinbase transaction script.
     *
     * @param script the transaction script to extract the nonce from.
     * @param pc     offset of the merkle branch size within the script (this is 4
     *               bytes before the start of the nonce value). Range checks should be
     *               performed before calling this method.
     * @return the nonce value.
     */
    protected static long getNonceFromScript(final byte[] script, final int pc) {
        // Note that the nonce value is packed as platform order (typically
        // little-endian) so we have to convert to big-endian for Java
        final byte[] nonceBytes = Utils.reverseBytes(Arrays.copyOfRange(script, pc + 4, pc + 8));

        return ByteBuffer.wrap(nonceBytes).getInt() & 0xffffffffl;
    }

    /**
     * Get the expected index of the slot within the chain merkle tree.
     * <p>
     * This prevents the same work from being used twice for the
     * same chain while reducing the chance that two chains clash
     * for the same slot.
     */
    protected static int getExpectedIndex(final long nonce, final int chainId, final int merkleHeight) {
        // Choose a pseudo-random slot in the chain merkle tree
        // but have it be fixed for a size/nonce/chain combination.

        // We do most of the maths with a signed 32 bit integer, as the operation is
        // the same as the 32 unsigned integer that the reference version uses
        int rand = (int) nonce;
        rand = rand * 1103515245 + 12345;
        rand += chainId;
        rand = rand * 1103515245 + 12345;

        // At this point, we need to flip the value to its positive version,
        // so we switch to a 64 bit signed integer for the last calculations
        long longRand = rand & 0xffffffffl;

        longRand %= (1 << merkleHeight);

        return (int) longRand;
    }

    public Transaction getTransaction() {
        return this.transaction;
    }

    /**
     * Test whether one array is at a specific offset within the other.
     *
     * @param script   the longer array to test for containing another array.
     * @param offset   the offset to start at within the larger array.
     * @param subArray the shorter array to test for presence in the longer array.
     * @return true if the shorter array is present at the offset, false otherwise.
     */
    static boolean arrayMatch(final byte[] script, final int offset, final byte[] subArray) {
        int matchIdx;
        for (matchIdx = 0; matchIdx + offset < script.length && matchIdx < subArray.length; matchIdx++) {
            if (script[offset + matchIdx] != subArray[matchIdx]) {
                return false;
            }
        }
        return matchIdx == subArray.length;
    }

    /**
     * Set the merkle branch used to connect the coinbase transaction to the
     * parent block header.
     */
    public void setCoinbaseBranch(final MerkleBranch merkleBranch) {
        this.coinbaseBranch = merkleBranch;
    }

    /**
     * Set the parent chain block header.
     */
    public void setParentBlockHeader(final AltcoinBlock header) {
        this.parentBlockHeader = header;
    }
}
