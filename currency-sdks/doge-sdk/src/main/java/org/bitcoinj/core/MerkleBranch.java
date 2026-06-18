/**
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
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

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Merkle branch contains the hashes from a leaf of a Merkle tree
 * up to its root, plus a bitset used to define how the hashes are applied.
 * Given the hash of the leaf, this can be used to calculate the tree
 * root. This is useful for proving that a leaf belongs to a given tree.
 * <p>
 * TODO: Has a lot of similarity to PartialMerkleTree, should attempt to merge
 * the two.
 */
public class MerkleBranch extends ChildMessage {
    private static final long serialVersionUID = 2;

    // Merkle branches can be encoded in a way that will use more bytes than is optimal
    // (due to VarInts having multiple encodings)
    // MAX_BLOCK_SIZE must be compared to the optimal encoding, not the actual encoding, so when parsing, we keep track
    // of the size of the ideal encoding in addition to the actual message size (which Message needs) so that Blocks
    // can properly keep track of optimal encoded size
    private transient int optimalEncodingMessageSize;

    private List<Sha256Hash> hashes;
    private long index;

    public MerkleBranch(final NetworkParameters params, @Nullable final ChildMessage parent) {
        super(params);
        this.setParent(parent);

        this.hashes = new ArrayList<>();
        this.index = 0;
    }

    /**
     * Deserializes an input message. This is usually part of a merkle branch message.
     */
    public MerkleBranch(final NetworkParameters params, @Nullable final ChildMessage parent, final byte[] payload, final int offset) throws ProtocolException {
        super(params, payload, offset);
        this.setParent(parent);
    }

    /**
     * Deserializes an input message. This is usually part of a merkle branch message.
     *
     * @param params     NetworkParameters object.
     * @param payload    Bitcoin protocol formatted byte array containing message content.
     * @param offset     The location of the first payload byte within the array.
     * @param serializer the serializer to use for this message.
     * @throws ProtocolException
     */
    public MerkleBranch(final NetworkParameters params, final ChildMessage parent, final byte[] payload, final int offset,
                        final MessageSerializer serializer)
            throws ProtocolException {
        super(params, payload, offset, parent, serializer, UNKNOWN_LENGTH);
    }

    public MerkleBranch(final NetworkParameters params, @Nullable final ChildMessage parent,
                        final List<Sha256Hash> hashes, final long branchSideMask) {
        super(params);
        this.setParent(parent);

        this.hashes = hashes;
        this.index = branchSideMask;
    }

    public static int calcLength(final byte[] buf, final int offset) {
        final VarInt varint = new VarInt(buf, offset);

        return ((int) varint.value) * 4 + 4;
    }

    @Override
    protected void parse() throws ProtocolException {
        this.cursor = this.offset;

        final int hashCount = (int) this.readVarInt();
        this.optimalEncodingMessageSize += VarInt.sizeOf(hashCount);
        this.hashes = new ArrayList<>(hashCount);
        for (int hashIdx = 0; hashIdx < hashCount; hashIdx++) {
            this.hashes.add(this.readHash());
        }
        this.optimalEncodingMessageSize += 32 * hashCount;
        this.setIndex(this.readUint32());
        this.optimalEncodingMessageSize += 4;
        this.length = this.cursor - this.offset;
    }

    @Override
    protected void bitcoinSerializeToStream(final OutputStream stream) throws IOException {
        stream.write(new VarInt(this.hashes.size()).encode());
        for (final Sha256Hash hash : this.hashes) {
            stream.write(Utils.reverseBytes(hash.getBytes()));
        }
        Utils.uint32ToByteStreamLE(this.index, stream);
    }

    /**
     * Calculate the merkle branch root based on the supplied hashes and the given leaf hash.
     * Used to verify that the given leaf and root are part of the same tree.
     */
    public Sha256Hash calculateMerkleRoot(final Sha256Hash leaf) {
        byte[] target = leaf.getReversedBytes();
        long mask = this.index;
        final MessageDigest digest = Sha256Hash.newDigest();

        for (final Sha256Hash hash : this.hashes) {
            digest.reset();
            if ((mask & 1) == 0) { // 0 means it goes on the right
                digest.update(target);
                digest.update(hash.getReversedBytes());
            } else {
                digest.update(hash.getReversedBytes());
                digest.update(target);
            }
            // Double-digest the values
            target = digest.digest();
            digest.reset();
            target = digest.digest(target);
            mask >>= 1;
        }
        return Sha256Hash.wrapReversed(target);
    }

    /**
     * Get the hashes which make up this branch.
     */
    public List<Sha256Hash> getHashes() {
        return Collections.unmodifiableList(this.hashes);
    }

    /**
     * Return the mask used to determine which side the hashes are applied on.
     * Each bit represents a hash. Zero means it goes on the right, one means
     * on the left.
     */
    public long getIndex() {
        return this.index;
    }

    /**
     * @param hashes the hashes to set
     */
    public void setHashes(final List<Sha256Hash> hashes) {
        this.hashes = hashes;
    }

    /**
     * Set the mask used to determine the sides in which hashes are applied.
     */
    public void setIndex(final long newIndex) {
        assert newIndex >= 0;
        this.index = newIndex;
    }

    /**
     * Get the number of hashes in this branch.
     */
    public int size() {
        return this.hashes.size();
    }

    public int getOptimalEncodingMessageSize() {
        if (this.optimalEncodingMessageSize != 0) {
            return this.optimalEncodingMessageSize;
        }
        if (this.optimalEncodingMessageSize != 0) {
            return this.optimalEncodingMessageSize;
        }
        this.optimalEncodingMessageSize = this.getMessageSize();
        return this.optimalEncodingMessageSize;
    }

    /**
     * Returns a human readable debug string.
     */
    @Override
    public String toString() {
        return "Merkle branch";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }

        final MerkleBranch input = (MerkleBranch) o;

        if (!this.hashes.equals(input.hashes)) {
            return false;
        }
        if (this.index != input.index) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + this.hashes.hashCode();
        result = 31 * result + (int) this.index;
        return result;
    }
}
