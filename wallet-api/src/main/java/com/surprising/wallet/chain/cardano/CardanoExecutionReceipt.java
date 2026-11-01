package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import utxorpc.v1beta.sync.Sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import com.surprising.wallet.common.chain.ChainScanPoint;
import utxorpc.v1beta.query.Query;

/** Original Conway header/body commitments. Canonical chain membership is supplied by native FollowTip. */
public final class CardanoExecutionReceipt {
    private static final HexFormat HEX = HexFormat.of();
    private CardanoExecutionReceipt() { }
    public record Transaction(int index, String hash, TransactionBody body, boolean successful, boolean auxiliary,
                              byte[] bodyBytes, byte[] witnessBytes) {
        public Transaction { bodyBytes = bodyBytes.clone(); witnessBytes = witnessBytes.clone(); }
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        @Override public byte[] witnessBytes() { return witnessBytes.clone(); }
    }
    public record Block(Sync.BlockRef reference, String parentHash, List<Transaction> transactions) {
        public Block { transactions = List.copyOf(transactions); }
    }
    public record Outcome(long blockHeight, long slot, String blockHash, java.math.BigInteger feeLovelace) { }
    public record Output(int index, TransactionOutput value, String originalCbor) { }

    /** Actual ledger outputs, retaining their original slices. Failed Plutus transactions create only collateral return. */
    public static List<Output> outputs(Transaction transaction) {
        try {
            var reader = new Reader(transaction.bodyBytes());
            long entries = reader.containerLength(5);
            List<Slice> ordinary = null;
            Slice collateral = null;
            var keys = new HashSet<Long>();
            for (long i = 0; entries < 0 ? !reader.breakIfPresent() : i < entries; i++) {
                long key = number(reader.item().value());
                require(keys.add(key), "Cardano transaction body has duplicate fields");
                if (key == 1) ordinary = reader.items().elements();
                else {
                    var value = reader.item();
                    if (key == 16) collateral = value;
                }
            }
            require(reader.finished() && ordinary != null, "Cardano original output list is absent");
            List<Output> outputs = new ArrayList<>();
            if (transaction.successful()) {
                for (int index = 0; index < ordinary.size(); index++) {
                    var output = ordinary.get(index);
                    outputs.add(new Output(index, TransactionOutput.deserialize(output.value()), HEX.formatHex(output.raw())));
                }
            } else if (collateral != null) {
                // The ledger appends collateral return after the ordinary output indices (CIP-40).
                outputs.add(new Output(ordinary.size(), TransactionOutput.deserialize(collateral.value()), HEX.formatHex(collateral.raw())));
            }
            return List.copyOf(outputs);
        } catch (Exception error) { throw new IllegalArgumentException("invalid Cardano original output proof", error); }
    }

    /**
     * The caller must have durably searched every canonical block from the fixed observation through searched.
     * A phase-one expiry is never a mined fee: all original inputs must still be unspent after the closed TTL.
     */
    public static Outcome expiredUnspent(Sync.AnyChainBlock stableBlock, MpcCardanoTransactionCodec.Unsigned unsigned,
                                         MpcCardanoTransactionCodec.Signed selected, ChainScanPoint searched,
                                         Query.ReadUtxosResponse unspent, int requiredConfirmations) {
        MpcCardanoTransactionCodec.verifySelected(unsigned, selected);
        var block = verifyBlock(stableBlock);
        require(CardanoRpcClient.point(block.reference()).equals(searched)
                && block.reference().getSlot() >= MpcCardanoTransactionCodec.expiry(unsigned),
                "Cardano expiry is not covered by the durable complete canonical search");
        require(block.transactions().stream().noneMatch(tx -> tx.hash().equals(selected.txHash())),
                "Cardano expired payment unexpectedly occurs in the searched block");
        require(unspent.hasLedgerTip() && unspent.getLedgerTip().getHash().size() == 32
                && unspent.getLedgerTip().getHeight() >= searched.height() && unspent.getLedgerTip().getSlot() >= searched.slot()
                && unspent.getLedgerTip().getHeight() - searched.height() + 1 >= Math.max(1, requiredConfirmations)
                && (unspent.getLedgerTip().getHeight() != searched.height()
                    || unspent.getLedgerTip().getHash().equals(block.reference().getHash())),
                "Cardano unspent snapshot precedes the stable expired search point");
        var expected = new java.util.HashMap<String, String>();
        for (var input : unsigned.inputs()) expected.put(input.transactionId() + ":" + input.index(), input.outputHex());
        require(unspent.getItemsCount() == expected.size(), "Cardano expired payment inputs are spent or missing");
        var observed = new HashSet<String>();
        for (var output : unspent.getItemsList()) {
            String key = HEX.formatHex(output.getTxoRef().getHash().toByteArray()) + ":" + output.getTxoRef().getIndex();
            require(observed.add(key) && expected.containsKey(key)
                    && HEX.formatHex(output.getNativeBytes().toByteArray()).equals(expected.get(key)),
                    "Cardano expired payment input reference or original output changed");
            require(CardanoPublicTransactionBuilder.output(output).getAddress().equals(unsigned.sender()),
                    "Cardano expired input no longer belongs to the selected source");
        }
        return new Outcome(searched.height(), searched.slot(), searched.hash(), java.math.BigInteger.ZERO);
    }

    public static Outcome verify(Sync.AnyChainBlock source, MpcCardanoTransactionCodec.Unsigned unsigned,
                                 MpcCardanoTransactionCodec.Signed selected) {
        MpcCardanoTransactionCodec.verifySelected(unsigned, selected);
        var block = verifyBlock(source);
        var matches = block.transactions().stream().filter(tx -> tx.hash().equals(selected.txHash())).toList();
        require(matches.size() == 1, "Cardano exact payment is absent or duplicated in the original block");
        var found = matches.getFirst();
        var envelope = (Array) MpcCardanoTransactionCodec.decode(HEX.parseHex(selected.transactionHex()));
        require(Arrays.equals(found.bodyBytes(), MpcCardanoTransactionCodec.encode(envelope.getDataItems().getFirst()))
                && Arrays.equals(found.witnessBytes(), MpcCardanoTransactionCodec.encode(envelope.getDataItems().get(1))),
                "Cardano original body or witness differs from selected wire");
        require(found.successful() && !found.auxiliary() && block.reference().getSlot() >= MpcCardanoTransactionCodec.validFrom(unsigned)
                && block.reference().getSlot() < MpcCardanoTransactionCodec.expiry(unsigned),
                "Cardano simple key payment is not valid within its original interval");
        require(found.body().getFee().equals(MpcCardanoTransactionCodec.fee(unsigned)), "Cardano actual original fee differs");
        return new Outcome(block.reference().getHeight(), block.reference().getSlot(),
                HEX.formatHex(block.reference().getHash().toByteArray()), found.body().getFee());
    }

    /** Keeps the original byte slices: decoding and re-encoding a header would not prove its actual hash. */
    public static Block verifyBlock(Sync.AnyChainBlock source) {
        require(source.hasCardano() && source.getCardano().hasHeader() && !source.getNativeBytes().isEmpty()
                && source.getNativeBytes().size() <= 16 * 1024 * 1024, "Cardano original block is absent or oversized");
        try {
            Reader reader = new Reader(source.getNativeBytes().toByteArray());
            require(reader.arrayLength() == 2 && number(reader.item().value()) == 7, "Cardano receipt requires the native Conway envelope");
            require(reader.arrayLength() == 5, "Cardano Conway block layout differs");
            Slice header = reader.item();
            var headerItems = array(header.value());
            require(headerItems.size() == 2, "Cardano header layout differs");
            var fields = array(headerItems.getFirst());
            require(fields.size() == 10, "Cardano Praos header body layout differs");
            long height = number(fields.get(0)), slot = number(fields.get(1)), declaredSize = number(fields.get(6));
            String parentHash = null;
            if (fields.get(2) instanceof ByteString parent) {
                require(parent.getBytes() != null && parent.getBytes().length == 32, "Cardano previous header hash is invalid");
                parentHash = HEX.formatHex(parent.getBytes());
            } else require(co.nstant.in.cbor.model.SimpleValue.NULL.equals(fields.get(2)), "Cardano previous header is not a hash or origin");
            var parsed = source.getCardano();
            byte[] hash = hash(header.raw());
            require(parsed.getHeader().getHash().equals(com.google.protobuf.ByteString.copyFrom(hash))
                    && parsed.getHeader().getHeight() == height && parsed.getHeader().getSlot() == slot,
                    "Cardano original header hash, height or slot differs");
            Part bodies = reader.items();
            Part witnesses = reader.items();
            Slice auxiliary = reader.item();
            Slice invalid = reader.item();
            require(reader.finished(), "Cardano native block has trailing data");
            require(bodies.elements().size() == witnesses.elements().size()
                    && parsed.getBody().getTxCount() == bodies.elements().size(), "Cardano block transaction/witness count differs");
            var commitment = new ByteArrayOutputStream();
            int size = 0;
            for (byte[] part : List.of(bodies.raw(), witnesses.raw(), auxiliary.raw(), invalid.raw())) {
                commitment.writeBytes(hash(part)); size = Math.addExact(size, part.length);
            }
            require(size == declaredSize && fields.get(7) instanceof ByteString
                    && Arrays.equals(hash(commitment.toByteArray()), ((ByteString) fields.get(7)).getBytes()),
                    "Cardano original block body commitment or size differs");
            var invalidIndices = new HashSet<Long>();
            for (var item : array(invalid.value())) {
                long index = number(item);
                require(index < bodies.elements().size() && invalidIndices.add(index), "Cardano invalid transaction index differs");
            }
            require(auxiliary.value() instanceof co.nstant.in.cbor.model.Map, "Cardano auxiliary body component is not a map");
            var auxiliaryIndices = new HashSet<Long>();
            for (var index : ((co.nstant.in.cbor.model.Map) auxiliary.value()).getKeys()) {
                long position = number(index);
                require(position < bodies.elements().size() && auxiliaryIndices.add(position), "Cardano auxiliary transaction index differs");
            }
            List<Transaction> transactions = new ArrayList<>();
            var hashes = new HashSet<String>();
            for (int index = 0; index < bodies.elements().size(); index++) {
                var original = bodies.elements().get(index);
                String txHash = HEX.formatHex(hash(original.raw()));
                var fact = parsed.getBody().getTx(index);
                require(hashes.add(txHash) && fact.getHash().equals(com.google.protobuf.ByteString.copyFrom(HEX.parseHex(txHash)))
                        && fact.getSuccessful() == !invalidIndices.contains((long) index),
                        "Cardano parsed transaction hash or result differs from original block");
                require(original.value() instanceof co.nstant.in.cbor.model.Map, "Cardano transaction body is not a map");
                var body = TransactionBody.deserialize((co.nstant.in.cbor.model.Map) original.value());
                transactions.add(new Transaction(index, txHash, body, !invalidIndices.contains((long) index), auxiliaryIndices.contains((long) index),
                        original.raw(), witnesses.elements().get(index).raw()));
            }
            return new Block(CardanoRpcClient.reference(parsed), parentHash, transactions);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid Cardano original block proof", e);
        }
    }

    private record Slice(DataItem value, byte[] raw) { }
    private record Part(List<Slice> elements, byte[] raw) { }
    /** The library decodes each item; this reader only retains its exact stream offsets and array boundaries. */
    private static final class Reader {
        private final byte[] bytes;
        private final ByteArrayInputStream input;
        private final CborDecoder decoder;
        private int count;
        Reader(byte[] bytes) { this.bytes = bytes; input = new ByteArrayInputStream(bytes); decoder = new CborDecoder(input); }
        int offset() { return bytes.length - input.available(); }
        boolean finished() { return input.available() == 0; }
        byte[] bytes(int start) { return Arrays.copyOfRange(bytes, start, offset()); }
        Slice item() throws Exception {
            require(++count <= 100_000, "Cardano original proof has too many top-level items");
            int start = offset();
            var item = decoder.decodeNext();
            require(item != null, "Cardano original proof is truncated");
            return new Slice(item, bytes(start));
        }
        long arrayLength() { return containerLength(4); }
        long containerLength(int major) {
            int initial = input.read();
            require(initial >= 0 && initial >>> 5 == major, "Cardano original proof expected container");
            int additional = initial & 31;
            if (additional < 24) return additional;
            if (additional == 31) return -1;
            require(additional <= 27, "invalid Cardano original array length");
            int length = 1 << (additional - 24); long value = 0;
            for (int i = 0; i < length; i++) {
                int octet = input.read(); require(octet >= 0, "Cardano original array length truncated");
                require(value <= (Long.MAX_VALUE >>> 8), "Cardano original array length overflow");
                value = (value << 8) | octet;
            }
            require(value <= 100_000, "Cardano original proof array is oversized");
            return value;
        }
        boolean breakIfPresent() {
            input.mark(1); int next = input.read();
            require(next >= 0, "Cardano indefinite container truncated");
            if (next == 255) return true;
            input.reset(); return false;
        }
        Part items() throws Exception {
            int start = offset(); long length = arrayLength();
            List<Slice> result = new ArrayList<>();
            if (length >= 0) for (long i = 0; i < length; i++) result.add(item());
            else while (true) {
                input.mark(1); int next = input.read(); require(next >= 0, "Cardano indefinite array truncated");
                if (next == 255) break;
                input.reset(); result.add(item());
            }
            return new Part(List.copyOf(result), bytes(start));
        }
    }
    private static long number(DataItem value) {
        require(value instanceof UnsignedInteger, "Cardano original proof expected unsigned integer");
        return ((UnsignedInteger) value).getValue().longValueExact();
    }
    private static List<DataItem> array(DataItem value) {
        require(value instanceof Array, "Cardano original proof expected array value");
        return ((Array) value).getDataItems();
    }
    private static byte[] hash(byte[] bytes) {
        var digest = new Blake2bDigest(256); digest.update(bytes, 0, bytes.length);
        byte[] result = new byte[32]; digest.doFinal(result, 0); return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
