package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

public class WitnessTransactionBuilder {
    private static final HexFormat HEX = HexFormat.of();
    private static final long RBF_SEQUENCE = 0xfffffffdL;
    private static final int DEFAULT_REQUIRED_SIGNATURES = 2;
    private static final int DEFAULT_TOTAL_PUBKEYS = 3;

    private final NetworkParameters params;
    private final WitnessSigner signer = new WitnessSigner();
    private final List<InputMeta> inputs = new ArrayList<>();
    private final List<OutputMeta> outputs = new ArrayList<>();
    private Transaction cachedTx;

    public WitnessTransactionBuilder(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        this.params = params;
    }

    public void addInput(String txId, int index, String witnessScriptHex, Coin value) {
        if (txId == null || txId.isEmpty() || witnessScriptHex == null || witnessScriptHex.isEmpty()
                || value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("invalid input");
        }
        inputs.add(new InputMeta(txId, index, witnessScriptHex, value));
        cachedTx = null;
    }

    public void addOutput(String address, Coin value) {
        if (address == null || address.isEmpty() || value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("invalid output");
        }
        try {
            outputs.add(new OutputMeta(Address.fromString(params, address), value));
            cachedTx = null;
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("bad address " + address, e);
        }
    }

    public String buildFirstSign(List<ECKey> keys) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one input is required");
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("at least one output is required");
        }
        if (keys == null || keys.size() != inputs.size()) {
            throw new IllegalArgumentException("key count must equal input count");
        }

        Transaction tx = new Transaction(params);
        for (InputMeta input : inputs) {
            TransactionOutPoint outPoint = new TransactionOutPoint(input.index, Sha256Hash.wrap(input.txId));
            TransactionInput txInput = new TransactionInput(tx, new byte[0], outPoint, input.value);
            tx.addInput(txInput.withSequence(RBF_SEQUENCE));
        }
        for (OutputMeta output : outputs) {
            tx.addOutput(output.value, output.address);
        }
        for (int i = 0; i < inputs.size(); i++) {
            InputMeta input = inputs.get(i);
            Script witnessScript = new Script(HEX.parseHex(input.witnessScriptHex));
            TransactionSignature signature = signer.signWitnessInput(tx, i, keys.get(i), witnessScript,
                    input.value, Transaction.SigHash.ALL);
            TransactionWitness witness = signer.assembleWitness(witnessScript, Collections.singletonList(signature));
            tx.replaceInput(i, tx.getInput(i).withWitness(witness));
        }
        cachedTx = tx;
        return HEX.formatHex(tx.bitcoinSerialize());
    }

    public String buildSecondSign(String hex, List<ECKey> keys, List<String> witnessScriptHexes, List<Coin> values) {
        Transaction tx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(hex)));
        int inputCount = tx.getInputs().size();
        if (keys == null || keys.size() != inputCount || witnessScriptHexes == null
                || witnessScriptHexes.size() != inputCount || values == null || values.size() != inputCount) {
            throw new IllegalArgumentException("metadata count must equal input count");
        }
        for (int i = 0; i < inputCount; i++) {
            TransactionInput input = tx.getInput(i);
            TransactionWitness existingWitness = input.getWitness();
            Script witnessScript = signer.extractWitnessScript(existingWitness);
            if (witnessScript == null) {
                witnessScript = new Script(HEX.parseHex(witnessScriptHexes.get(i)));
            }
            Coin value = values.get(i);
            TransactionSignature signature = signer.signWitnessInput(tx, i, keys.get(i), witnessScript,
                    value, Transaction.SigHash.ALL);
            int required = witnessScript.getNumberOfSignaturesRequiredToSpend();
            TransactionWitness witness = signer.mergeMultisigWitness(tx, i, existingWitness, signature,
                    keys.get(i), witnessScript, value, required);
            tx.replaceInput(i, input.withWitness(witness));
        }
        cachedTx = tx;
        return HEX.formatHex(tx.bitcoinSerialize());
    }

    public Transaction getTransaction() {
        return cachedTx;
    }

    public String getHash() {
        return cachedTx == null ? null : cachedTx.getTxId().toString();
    }

    public int getVsize() {
        if (cachedTx == null) {
            throw new IllegalStateException("transaction has not been built");
        }
        return cachedTx.getVsize();
    }

    public static long estimateVBytes(int inputs, int outputs) {
        return estimateVBytes(inputs, outputs, DEFAULT_REQUIRED_SIGNATURES, DEFAULT_TOTAL_PUBKEYS);
    }

    public static long estimateVBytes(int inputs, int outputs, int requiredSignatures, int totalPubKeys) {
        if (inputs < 1 || outputs < 1 || requiredSignatures < 1 || totalPubKeys < requiredSignatures) {
            throw new IllegalArgumentException("invalid P2WSH multisig dimensions");
        }
        long witnessScriptSize = 3L + 34L * totalPubKeys;
        long witnessPerInput = 1L + 1L + requiredSignatures * 74L + varIntSize(witnessScriptSize) + witnessScriptSize;
        long witnessBytes = 2L + inputs * witnessPerInput;
        long baseBytes = 4L + varIntSize(inputs) + inputs * 41L + varIntSize(outputs) + outputs * 43L + 4L;
        long weight = baseBytes * 4L + witnessBytes;
        return (weight + 3L) / 4L;
    }

    private static long varIntSize(long value) {
        if (value < 0xfdL) {
            return 1L;
        }
        if (value <= 0xffffL) {
            return 3L;
        }
        if (value <= 0xffffffffL) {
            return 5L;
        }
        return 9L;
    }

    private static class InputMeta {
        private final String txId;
        private final int index;
        private final String witnessScriptHex;
        private final Coin value;

        private InputMeta(String txId, int index, String witnessScriptHex, Coin value) {
            this.txId = txId;
            this.index = index;
            this.witnessScriptHex = witnessScriptHex;
            this.value = value;
        }
    }

    private static class OutputMeta {
        private final Address address;
        private final Coin value;

        private OutputMeta(Address address, Coin value) {
            this.address = address;
            this.value = value;
        }
    }
}
