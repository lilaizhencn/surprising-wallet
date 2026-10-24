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
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Native SegWit（P2WSH）多签交易构建器，负责构建和签署隔离见证多签交易。
 * 支持两阶段签名流程：
 *
 * <ul>
 *   <li><b>第一阶段（{@link #buildFirstSign}）</b>：构建原始交易并对每个输入进行首次签名，
 *       生成包含单个签名的半签名交易（hex格式）</li>
 *   <li><b>第二阶段（{@link #buildSecondSign}）</b>：在半签名交易基础上追加第二个签名，
 *       支持多签阈值（m-of-n）验证，自动将新签名合并到正确的公钥位置</li>
 * </ul>
 *
 * <p>技术细节：</p>
 * <ul>
 *   <li>所有输入默认启用RBF（Replace-By-Fee），sequence设为{@code 0xfffffffd}</li>
 *   <li>签名使用{@link WitnessSigner}进行witness签名计算与组装</li>
 *   <li>支持虚拟字节（vsize）估算，用于费率计算</li>
 *   <li>输入和输出通过内部{@code InputMeta}/{@code OutputMeta}元数据类管理</li>
 * </ul>
 */
public class WitnessTransactionBuilder {
    private static final HexFormat HEX = HexFormat.of();
    private static final long RBF_SEQUENCE = 0xfffffffdL;

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
            Script providedWitnessScript = new Script(HEX.parseHex(witnessScriptHexes.get(i)));
            if (witnessScript != null && !Arrays.equals(witnessScript.program(), providedWitnessScript.program())) {
                throw new IllegalArgumentException("provided witnessScript does not match transaction witness");
            }
            if (witnessScript == null) {
                witnessScript = providedWitnessScript;
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
        return P2wshFeeCalculator.estimateVBytes(inputs, outputs);
    }

    public static long estimateVBytes(int inputs, int outputs, int requiredSignatures, int totalPubKeys) {
        return P2wshFeeCalculator.estimateVBytes(inputs, outputs, requiredSignatures, totalPubKeys);
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
