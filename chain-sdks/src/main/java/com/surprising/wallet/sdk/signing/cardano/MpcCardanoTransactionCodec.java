package com.surprising.wallet.sdk.signing.cardano;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import org.bitcoinj.base.Bech32;
import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed public payment body, Blake2b-256 preimage and independently verified external Ed25519 witness. */
public final class MpcCardanoTransactionCodec {
    private static final HexFormat HEX = HexFormat.of();
    private static final Set<Long> PAYMENT_FIELDS = Set.of(0L, 1L, 2L, 3L, 8L, 15L);
    private MpcCardanoTransactionCodec() { }

    public static byte[] addressBytes(String address) {
        String prefix = address != null && address.startsWith("addr_test1") ? "addr_test" : "addr";
        byte[] bytes = Bech32.decodeBytes(address, prefix, Bech32.Encoding.BECH32);
        int type = bytes.length == 0 ? -1 : (bytes[0] & 0xff) >>> 4;
        if (!((type >= 0 && type <= 3 && bytes.length == 57) || ((type == 6 || type == 7) && bytes.length == 29))
                || (bytes[0] & 15) != (prefix.equals("addr") ? 1 : 0))
            throw new IllegalArgumentException("unsupported Cardano payment address");
        return bytes;
    }

    public static String assetUnit(String contract) { return CardanoAssetUnit.fromTokenContract(contract); }

    public record Input(String transactionId, int index, String outputHex) {
        public Input {
            require(transactionId != null && transactionId.matches("[0-9a-f]{64}") && index >= 0,
                    "invalid Cardano input reference");
            require(outputHex != null && outputHex.matches("(?:[0-9a-f]{2})+"), "Cardano input output fact is absent");
        }
    }

    public record Unsigned(String genesisHash, long networkMagic, String sender, String publicKeyHex,
                           String recipient, String unit, BigInteger amount, String transactionHex, List<Input> inputs) {
        public Unsigned {
            require(genesisHash != null && genesisHash.matches("[0-9a-f]{64}") && networkMagic >= 0
                    && networkMagic <= 0xffff_ffffL, "invalid Cardano network identity");
            require(publicKeyHex != null && publicKeyHex.matches("[0-9a-f]{64}"), "invalid Cardano joint public key");
            byte[] source = addressBytes(sender);
            byte[] target = addressBytes(recipient);
            require(!Arrays.equals(source, target) && (source[0] & 15) == (target[0] & 15),
                    "Cardano source/recipient or network differs");
            require(sender.equals(address(HEX.parseHex(publicKeyHex), (source[0] & 15) == 1)),
                    "Cardano source is not the joint payment key");
            unit = CardanoAssetUnit.LOVELACE.equals(unit) ? unit : CardanoAssetUnit.fromTokenContract(unit);
            require(amount != null && amount.signum() > 0, "Cardano principal must be positive");
            require(transactionHex != null && transactionHex.matches("(?:[0-9a-f]{2})+")
                    && transactionHex.length() <= 131_072, "invalid Cardano unsigned bytes");
            inputs = List.copyOf(inputs);
            require(!inputs.isEmpty() && inputs.size() <= 1_000, "invalid Cardano input count");
        }
    }

    public record Signed(String transactionHex, String txHash) { }

    public static byte[] signingBytes(Unsigned unsigned) {
        return digest(bodyBytes(unsigned), 256);
    }

    public static String txHash(Unsigned unsigned) { return HEX.formatHex(signingBytes(unsigned)); }

    public static Signed assemble(Unsigned unsigned, byte[] signature) {
        byte[] publicKey = HEX.parseHex(unsigned.publicKeyHex());
        require(signature != null && signature.length == 64
                && ThresholdSignatureVerifier.ed25519(publicKey, signingBytes(unsigned), signature),
                "Cardano MPC witness differs from the immutable body and public key");
        byte[] raw = witnessedBytes(unsigned, signature);
        return new Signed(HEX.formatHex(raw), txHash(unsigned));
    }

    public static int signedSize(Unsigned unsigned) {
        return witnessedBytes(unsigned, new byte[64]).length;
    }

    private static byte[] witnessedBytes(Unsigned unsigned, byte[] signature) {
        Array envelope = envelope(unsigned);
        byte[] publicKey = HEX.parseHex(unsigned.publicKeyHex());
        var witnesses = new co.nstant.in.cbor.model.Map();
        var keys = new Array().add(new Array().add(new ByteString(publicKey)).add(new ByteString(signature.clone())));
        // Conway ledger encodes witness sets as CBOR sets.
        keys.setTag(258);
        witnesses.put(new UnsignedInteger(0), keys);
        envelope.getDataItems().set(1, witnesses);
        byte[] raw = encode(envelope);
        require(Arrays.equals(bodyBytes(unsigned), encode(envelope.getDataItems().getFirst())),
                "Cardano witness assembly changed the original body");
        return raw;
    }

    public static void verifySelected(Unsigned unsigned, Signed selected) {
        require(selected != null && txHash(unsigned).equals(selected.txHash()), "Cardano selected hash differs");
        var array = array(decode(HEX.parseHex(selected.transactionHex())));
        require(array.getDataItems().size() == 4 && Arrays.equals(encode(array.getDataItems().getFirst()), bodyBytes(unsigned)),
                "Cardano selected body differs");
        try {
            var transaction = Transaction.deserialize(HEX.parseHex(selected.transactionHex()));
            var witnesses = transaction.getWitnessSet().getVkeyWitnesses();
            require(witnesses.size() == 1 && Arrays.equals(witnesses.getFirst().getVkey(), HEX.parseHex(unsigned.publicKeyHex())),
                    "Cardano selected witness key differs");
            var rebuilt = assemble(unsigned, witnesses.getFirst().getSignature());
            require(rebuilt.equals(selected), "Cardano selected witness envelope differs");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid Cardano selected wire", e);
        }
    }

    public static BigInteger fee(Unsigned unsigned) { return transaction(unsigned).getBody().getFee(); }
    public static long expiry(Unsigned unsigned) { return transaction(unsigned).getBody().getTtl(); }
    public static long validFrom(Unsigned unsigned) { return transaction(unsigned).getBody().getValidityStartInterval(); }
    public static BigInteger recipientLovelace(Unsigned unsigned) {
        return transaction(unsigned).getBody().getOutputs().stream().filter(o -> o.getAddress().equals(unsigned.recipient()))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    public static Transaction transaction(Unsigned unsigned) {
        bodyBytes(unsigned);
        try { return Transaction.deserialize(HEX.parseHex(unsigned.transactionHex())); }
        catch (Exception e) { throw new IllegalArgumentException("invalid Cardano transaction", e); }
    }

    private static byte[] bodyBytes(Unsigned unsigned) {
        Array envelope = envelope(unsigned);
        var body = (co.nstant.in.cbor.model.Map) envelope.getDataItems().getFirst();
        for (var key : body.getKeys()) {
            require(key instanceof UnsignedInteger && PAYMENT_FIELDS.contains(((UnsignedInteger) key).getValue().longValueExact()),
                    "Cardano payment contains unsupported scripts, governance or body fields");
        }
        require(body.get(new UnsignedInteger(0)) != null && body.get(new UnsignedInteger(1)) != null
                && body.get(new UnsignedInteger(2)) != null && body.get(new UnsignedInteger(3)) != null,
                "Cardano payment input, output, fee or expiry is absent");
        try {
            var transaction = Transaction.deserialize(HEX.parseHex(unsigned.transactionHex()));
            var tx = transaction.getBody();
            require(tx.getFee().signum() >= 0 && tx.getTtl() > tx.getValidityStartInterval()
                    && tx.getValidityStartInterval() >= 0, "invalid Cardano fee or validity interval");
            byte[] source = addressBytes(unsigned.sender());
            if (tx.getNetworkId() != null) require((tx.getNetworkId() == com.bloxbean.cardano.client.spec.NetworkId.MAINNET ? 1 : 0)
                    == (source[0] & 15), "Cardano body network differs");
            Map<String, Input> facts = new HashMap<>();
            for (var fact : unsigned.inputs())
                require(facts.put(fact.transactionId() + ":" + fact.index(), fact) == null, "duplicate Cardano input fact");
            Map<String, BigInteger> inputs = new HashMap<>();
            Set<String> consumed = new HashSet<>();
            for (var input : tx.getInputs()) {
                String reference = input.getTransactionId() + ":" + input.getIndex();
                var fact = facts.get(reference);
                require(fact != null && consumed.add(reference), "Cardano input is absent from the fixed UTxO snapshot or duplicated");
                var output = TransactionOutput.deserialize(decode(HEX.parseHex(fact.outputHex())));
                require(unsigned.sender().equals(output.getAddress()), "Cardano input does not belong to the joint payment address");
                simpleOutput(output);
                add(inputs, output.getValue());
            }
            require(consumed.size() == facts.size() && !consumed.isEmpty(), "Cardano fixed input set differs");
            Map<String, BigInteger> outputs = new HashMap<>();
            int recipients = 0;
            for (var output : tx.getOutputs()) {
                simpleOutput(output);
                byte[] bytes = addressBytes(output.getAddress());
                require((bytes[0] & 15) == (source[0] & 15), "Cardano output network differs");
                if (unsigned.recipient().equals(output.getAddress())) {
                    recipients++;
                    Map<String, BigInteger> delivered = new HashMap<>();
                    add(delivered, output.getValue());
                    require(unsigned.amount().equals(delivered.get(unsigned.unit())), "Cardano recipient principal differs");
                    delivered.remove(CardanoAssetUnit.LOVELACE);
                    if (!unsigned.unit().equals(CardanoAssetUnit.LOVELACE)) delivered.remove(unsigned.unit());
                    require(delivered.isEmpty(), "Cardano recipient contains an unauthorized asset");
                } else require(unsigned.sender().equals(output.getAddress()), "Cardano output is neither the recipient nor owned change");
                add(outputs, output.getValue());
            }
            require(recipients == 1, "Cardano payment needs exactly one principal output");
            outputs.merge(CardanoAssetUnit.LOVELACE, tx.getFee(), BigInteger::add);
            require(inputs.equals(outputs), "Cardano inputs, change, principal and fee do not conserve assets");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid Cardano payment body", e);
        }
        return encode(body);
    }

    private static Array envelope(Unsigned unsigned) {
        byte[] raw = HEX.parseHex(unsigned.transactionHex());
        Array envelope = array(decode(raw));
        require(envelope.getDataItems().size() == 4 && envelope.getDataItems().getFirst() instanceof co.nstant.in.cbor.model.Map
                && envelope.getDataItems().get(1) instanceof co.nstant.in.cbor.model.Map
                && ((co.nstant.in.cbor.model.Map) envelope.getDataItems().get(1)).getKeys().isEmpty()
                && SimpleValue.TRUE.equals(envelope.getDataItems().get(2)) && SimpleValue.NULL.equals(envelope.getDataItems().get(3)),
                "Cardano payment must be unsigned, valid and contain no auxiliary data");
        require(Arrays.equals(raw, encode(envelope)), "Cardano unsigned CBOR is not canonical");
        return envelope;
    }

    private static void simpleOutput(TransactionOutput output) {
        require(output.getDatumHash() == null && output.getInlineDatum() == null && output.getScriptRef() == null,
                "Cardano simple payment contains datum or reference script");
        require(output.getValue() != null && output.getValue().getCoin().signum() > 0, "Cardano output coin must be positive");
    }

    private static void add(Map<String, BigInteger> totals, Value value) {
        totals.merge(CardanoAssetUnit.LOVELACE, value.getCoin(), BigInteger::add);
        Set<String> units = new HashSet<>();
        for (var policy : value.getMultiAssets()) {
            require(policy.getPolicyId().matches("[0-9a-f]{56}"), "invalid Cardano asset policy");
            for (var asset : policy.getAssets()) {
                String unit = CardanoAssetUnit.fromTokenContract(policy.getPolicyId() + "." + asset.getNameAsHex());
                require(units.add(unit) && asset.getValue().signum() > 0, "invalid or duplicate Cardano output asset");
                totals.merge(unit, asset.getValue(), BigInteger::add);
            }
        }
    }

    public static String address(byte[] publicKey, boolean mainnet) {
        require(publicKey != null && publicKey.length == 32, "invalid Cardano payment public key");
        byte[] payload = new byte[29];
        payload[0] = (byte) (mainnet ? 0x61 : 0x60);
        System.arraycopy(digest(publicKey, 224), 0, payload, 1, 28);
        return Bech32.encodeBytes(Bech32.Encoding.BECH32, mainnet ? "addr" : "addr_test", payload);
    }

    public static byte[] encode(DataItem item) {
        try { return CborSerializationUtil.serialize(item); }
        catch (Exception e) { throw new IllegalArgumentException("Cardano CBOR encode failed", e); }
    }

    public static DataItem decode(byte[] bytes) {
        try {
            var items = CborDecoder.decode(bytes);
            require(items.size() == 1, "Cardano CBOR has trailing data");
            return items.getFirst();
        } catch (Exception e) { throw new IllegalArgumentException("Cardano CBOR decode failed", e); }
    }

    private static Array array(DataItem item) { require(item instanceof Array, "Cardano CBOR envelope is not an array"); return (Array) item; }
    private static byte[] digest(byte[] bytes, int bits) {
        var digest = new Blake2bDigest(bits);
        digest.update(bytes, 0, bytes.length);
        byte[] result = new byte[bits / 8];
        digest.doFinal(result, 0);
        return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
