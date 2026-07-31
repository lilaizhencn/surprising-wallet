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
 * 负责构建交易、脚本或请求对象，并执行必要的输入校验。
 */
public final class LegacyMultisigTransactionBuilder {
    /**
     * 定义 {@code HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final HexFormat HEX = HexFormat.of();
    /**
     * 定义 {@code RBF_SEQUENCE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final long RBF_SEQUENCE = 0xfffffffdL;

    /**
     * 保存 {@code params}，用于承载当前对象的运行配置或业务数据。
     */
    private final NetworkParameters params;
    /**
     * 保存 {@code inputs}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<InputMeta> inputs = new ArrayList<>();
    /**
     * 保存 {@code outputs}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<OutputMeta> outputs = new ArrayList<>();
    /**
     * 保存 {@code cachedTx}，用于保存业务集合或索引状态。
     */
    private Transaction cachedTx;

    /**
     * 构造 {@code LegacyMultisigTransactionBuilder}，初始化该组件运行所需的状态和依赖。
     */
    public LegacyMultisigTransactionBuilder(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        this.params = params;
    }

    /**
     * 添加 {@code addInput} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public void addInput(String txId, int index, String redeemScriptHex, Coin value) {
        if (txId == null || txId.isBlank() || redeemScriptHex == null || redeemScriptHex.isBlank()
                || value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("invalid input");
        }
        inputs.add(new InputMeta(txId, index, redeemScriptHex, value));
        cachedTx = null;
    }

    /**
     * 添加 {@code addOutput} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public void addOutput(String address, Coin value) {
        if (address == null || address.isBlank() || value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("invalid output");
        }
        outputs.add(new OutputMeta(LegacyAddress.fromBase58(params, address), value));
        cachedTx = null;
    }

    /**
     * 构建或生成 {@code buildFirstSign} 对应的结果，并执行输入和状态校验。
     */
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

    /**
     * 构建或生成 {@code buildSecondSign} 对应的结果，并执行输入和状态校验。
     */
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

    /**
     * 获取或查询 {@code getTransaction} 对应的数据，供调用方读取当前状态。
     */
    public Transaction getTransaction() {
        return cachedTx;
    }

    /**
     * 获取或查询 {@code getTxId} 对应的数据，供调用方读取当前状态。
     */
    public String getTxId() {
        return cachedTx == null ? null : cachedTx.getTxId().toString();
    }

    /**
     * 构建或生成 {@code createUnsignedTransaction} 对应的结果，并执行输入和状态校验。
     */
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

    /**
     * 校验 {@code validateKeyCount} 对应的前置条件，不满足时抛出明确异常。
     */
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
