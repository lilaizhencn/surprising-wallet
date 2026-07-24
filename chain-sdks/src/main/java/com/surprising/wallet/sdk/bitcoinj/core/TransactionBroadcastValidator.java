package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.script.Script;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 交易广播前验证器，用于在将已签名的比特币交易广播到网络之前对其进行完整性校验。
 * 专注于Native SegWit（P2WSH）多签交易的验证，校验内容包括：
 *
 * <ul>
 *   <li>交易基本格式和网络参数验证（{@link org.bitcoinj.core.Transaction#verify}）</li>
 *   <li>输入项不为空</li>
 *   <li>所有输入的scriptSig为空（SegWit特征）</li>
 *   <li>无重复输入（UTXO双花检测）</li>
 *   <li>Witness结构完整性（最少3个push：dummy、签名、witnessScript）</li>
 *   <li>多签签名数量是否满足阈值要求</li>
 *   <li>输出是否为粉尘（dust）交易</li>
 * </ul>
 *
 * <p>验证结果通过{@link ValidationResult}返回，包含有效性标志、错误列表、输入/签名计数和交易ID。</p>
 */
public class TransactionBroadcastValidator {
    private static final HexFormat HEX = HexFormat.of();

    private final NetworkParameters params;

    public TransactionBroadcastValidator(NetworkParameters params) {
        this.params = params;
    }

    public ValidationResult validate(String hex) {
        List<String> errors = new ArrayList<>();
        int inputCount = 0;
        int signatureCount = 0;
        String txId = null;
        try {
            Transaction tx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(hex)));
            if (params != null) {
                Transaction.verify(params, tx);
            }
            txId = tx.getTxId().toString();
            inputCount = tx.getInputs().size();
            if (inputCount == 0) {
                errors.add("no inputs");
            }

            Set<String> seenInputs = new HashSet<>();
            for (int i = 0; i < inputCount; i++) {
                TransactionInput input = tx.getInput(i);
                if (input.getScriptSig().program().length > 0) {
                    errors.add("input " + i + " has non-empty scriptSig");
                }
                TransactionOutPoint outPoint = input.getOutpoint();
                String outPointKey = outPoint.hash() + ":" + outPoint.index();
                if (!seenInputs.add(outPointKey)) {
                    errors.add("duplicate input " + outPointKey);
                }

                TransactionWitness witness = input.getWitness();
                if (witness == null || witness.getPushCount() < 3) {
                    errors.add("input " + i + " invalid witness");
                    continue;
                }
                if (witness.getPush(0).length != 0) {
                    errors.add("input " + i + " invalid multisig dummy push");
                }
                byte[] witnessScriptBytes = witness.getPush(witness.getPushCount() - 1);
                if (witnessScriptBytes.length == 0) {
                    errors.add("input " + i + " missing witnessScript");
                    continue;
                }
                Script witnessScript = new Script(witnessScriptBytes);
                byte[] witnessProgram = Sha256Hash.hash(witnessScript.program());
                if (witnessProgram.length != 32) {
                    errors.add("input " + i + " invalid P2WSH program");
                }
                int signatures = 0;
                for (int j = 1; j < witness.getPushCount() - 1; j++) {
                    if (witness.getPush(j).length > 0) {
                        signatures++;
                    }
                }
                signatureCount += signatures;
                int required = witnessScript.getNumberOfSignaturesRequiredToSpend();
                if (signatures < required) {
                    errors.add("input " + i + " signatures " + signatures + " < required " + required);
                }
            }

            for (int i = 0; i < tx.getOutputs().size(); i++) {
                if (tx.getOutput(i).isDust()) {
                    errors.add("output " + i + " is dust");
                }
            }
        } catch (Exception e) {
            errors.add("exception: " + e.getMessage());
        }
        return new ValidationResult(errors.isEmpty(), errors, inputCount, signatureCount, txId);
    }

    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final int inputCount;
        public final int signatureCount;
        public final String txId;

        public ValidationResult(boolean valid, List<String> errors, int inputCount, int signatureCount, String txId) {
            this.valid = valid;
            this.errors = errors == null ? new ArrayList<>() : errors;
            this.inputCount = inputCount;
            this.signatureCount = signatureCount;
            this.txId = txId;
        }

        @Override
        public String toString() {
            return "ValidationResult{valid=" + valid + ", txId='" + txId + "', inputCount="
                    + inputCount + ", signatureCount=" + signatureCount + ", errors=" + errors + '}';
        }
    }
}
