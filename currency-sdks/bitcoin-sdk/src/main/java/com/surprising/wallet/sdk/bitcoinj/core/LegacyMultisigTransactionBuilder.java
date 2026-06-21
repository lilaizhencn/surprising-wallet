package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

/**
 * Two-stage legacy P2SH multisig transaction builder for Dogecoin-like chains.
 */
public final class LegacyMultisigTransactionBuilder {
    private static final HexFormat HEX = HexFormat.of();
    private static final long RBF_SEQUENCE = 0xfffffffdL;

    private final NetworkParameters params;
    private final List<InputMeta> inputs = new ArrayList<>();
    private final List<OutputMeta> outputs = new ArrayList<>();
    private Transaction cachedTx;

    public LegacyMultisigTransactionBuilder(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        this.params = params;
    }

    public void addInput(String txId, int index, String redeemScriptHex, Coin value) {
        if (txId == null || txId.isBlank() || redeemScriptHex == null || redeemScriptHex.isBlank()
                || value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("invalid input");
        }
        inputs.add(new InputMeta(txId, index, redeemScriptHex, value));
        cachedTx = null;
    }

    public void addOutput(String address, Coin value) {
        if (address == null || address.isBlank() || value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("invalid output");
        }
        outputs.add(new OutputMeta(LegacyAddress.fromBase58(params, address), value));
        cachedTx = null;
    }

    public String buildFirstSign(List<ECKey> keys) {
        validateKeyCount(keys);
        Transaction tx = createUnsignedTransaction();
        for (int i = 0; i < inputs.size(); i++) {
            InputMeta input = inputs.get(i);
            Script redeemScript = new Script(HEX.parseHex(input.redeemScriptHex));
            Script outputScript = ScriptBuilder.createP2SHOutputScript(redeemScript);
            TransactionSignature signature = tx.calculateSignature(
                    i, keys.get(i), redeemScript, Transaction.SigHash.ALL, false);
            Script emptyScriptSig = ScriptBuilder.createP2SHMultiSigInputScript(null, redeemScript);
            Sha256Hash signatureHash = tx.hashForSignature(
                    i, redeemScript, Transaction.SigHash.ALL, false);
            int insertionIndex = emptyScriptSig.getSigInsertionIndex(signatureHash, keys.get(i));
            Script scriptSig = outputScript.getScriptSigWithSignature(
                    emptyScriptSig, signature.encodeToBitcoin(), insertionIndex);
            tx.replaceInput(i, tx.getInput(i).withScriptSig(scriptSig));
        }
        cachedTx = tx;
        return HEX.formatHex(tx.bitcoinSerialize());
    }

    public String buildSecondSign(String firstSignedHex, List<ECKey> keys, List<String> redeemScriptHexes) {
        Transaction tx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(firstSignedHex)));
        if (keys == null || redeemScriptHexes == null
                || keys.size() != tx.getInputs().size()
                || redeemScriptHexes.size() != tx.getInputs().size()) {
            throw new IllegalArgumentException("metadata count must equal input count");
        }
        for (int i = 0; i < tx.getInputs().size(); i++) {
            TransactionInput input = tx.getInput(i);
            Script redeemScript = new Script(HEX.parseHex(redeemScriptHexes.get(i)));
            Script outputScript = ScriptBuilder.createP2SHOutputScript(redeemScript);
            TransactionSignature signature = tx.calculateSignature(
                    i, keys.get(i), redeemScript, Transaction.SigHash.ALL, false);
            Sha256Hash signatureHash = tx.hashForSignature(
                    i, redeemScript, Transaction.SigHash.ALL, false);
            Script existingScriptSig = input.getScriptSig();
            int insertionIndex = existingScriptSig.getSigInsertionIndex(signatureHash, keys.get(i));
            Script completedScriptSig = outputScript.getScriptSigWithSignature(
                    existingScriptSig, signature.encodeToBitcoin(), insertionIndex);
            tx.replaceInput(i, input.withScriptSig(completedScriptSig));
            completedScriptSig.correctlySpends(
                    tx, i, outputScript,
                    EnumSet.of(Script.VerifyFlag.P2SH, Script.VerifyFlag.DERSIG, Script.VerifyFlag.NULLDUMMY));
        }
        cachedTx = tx;
        return HEX.formatHex(tx.bitcoinSerialize());
    }

    public Transaction getTransaction() {
        return cachedTx;
    }

    public String getTxId() {
        return cachedTx == null ? null : cachedTx.getTxId().toString();
    }

    private Transaction createUnsignedTransaction() {
        if (inputs.isEmpty() || outputs.isEmpty()) {
            throw new IllegalArgumentException("transaction requires inputs and outputs");
        }
        Transaction tx = new Transaction(params);
        for (InputMeta input : inputs) {
            TransactionOutPoint outPoint = new TransactionOutPoint(input.index, Sha256Hash.wrap(input.txId));
            tx.addInput(new TransactionInput(tx, new byte[0], outPoint, input.value)
                    .withSequence(RBF_SEQUENCE));
        }
        for (OutputMeta output : outputs) {
            tx.addOutput(output.value, output.address);
        }
        return tx;
    }

    private void validateKeyCount(List<ECKey> keys) {
        if (keys == null || keys.size() != inputs.size()) {
            throw new IllegalArgumentException("key count must equal input count");
        }
    }

    private record InputMeta(String txId, int index, String redeemScriptHex, Coin value) {
    }

    private record OutputMeta(LegacyAddress address, Coin value) {
    }
}
