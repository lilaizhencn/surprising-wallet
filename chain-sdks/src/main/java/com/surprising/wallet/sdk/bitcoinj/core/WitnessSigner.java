package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.SignatureDecodeException;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SegWit见证数据签名器，专注于Native SegWit（P2WSH）多签交易的见证（witness）签名
 * 生成与组装。基于secp256k1椭圆曲线的ECDSA签名算法，提供以下核心功能：
 *
 * <ul>
 *   <li><b>签名计算</b>：使用{@link org.bitcoinj.core.Transaction#calculateWitnessSignature}
 *       对指定输入计算witness签名，签名哈希类型默认为SIGHASH_ALL</li>
 *   <li><b>见证数据组装</b>：按P2WSH规范组装witness结构（OP_0 + 签名列表 + witnessScript）</li>
 *   <li><b>多签见证合并</b>：将新签名按公钥在witnessScript中的位置插入已有的多签witness中，
 *       自动去重并验证签名有效性</li>
 *   <li><b>工具方法</b>：提取witnessScript、统计签名数量、编码签名等</li>
 * </ul>
 *
 * <p>P2WSH见证数据结构：{@code [dummy(0x00), sig1, sig2, ..., sigM, witnessScript]}</p>
 */
public class WitnessSigner {
    private static final byte[] EMPTY = new byte[0];

    public TransactionSignature signWitnessInput(Transaction tx, int index, ECKey key, Script witnessScript,
                                                 Coin value, Transaction.SigHash hashType) {
        if (tx == null || key == null || witnessScript == null || value == null || hashType == null) {
            throw new IllegalArgumentException("signing parameters must not be null");
        }
        return tx.calculateWitnessSignature(index, key, witnessScript, value, hashType, false);
    }

    public TransactionWitness assembleWitness(Script witnessScript, List<TransactionSignature> signatures) {
        if (witnessScript == null || signatures == null) {
            throw new IllegalArgumentException("witness script and signatures are required");
        }
        List<byte[]> pushes = new ArrayList<>(signatures.size() + 2);
        pushes.add(EMPTY);
        for (TransactionSignature signature : signatures) {
            if (signature != null) {
                pushes.add(signature.encodeToBitcoin());
            }
        }
        pushes.add(witnessScript.program());
        return TransactionWitness.of(pushes);
    }

    public TransactionWitness mergeWitness(TransactionWitness existingWitness, TransactionSignature newSignature, int slot) {
        if (existingWitness == null || newSignature == null) {
            throw new IllegalArgumentException("witness and signature are required");
        }
        int pushCount = existingWitness.getPushCount();
        if (pushCount < 2) {
            throw new IllegalArgumentException("invalid P2WSH witness");
        }
        byte[] witnessScript = existingWitness.getPush(pushCount - 1);
        List<byte[]> signatures = new ArrayList<>();
        for (int i = 1; i < pushCount - 1; i++) {
            byte[] push = existingWitness.getPush(i);
            if (push.length > 0) {
                signatures.add(push);
            }
        }
        int insertAt = Math.max(0, Math.min(slot, signatures.size()));
        signatures.add(insertAt, newSignature.encodeToBitcoin());

        List<byte[]> pushes = new ArrayList<>(signatures.size() + 2);
        pushes.add(EMPTY);
        pushes.addAll(signatures);
        pushes.add(witnessScript);
        return TransactionWitness.of(pushes);
    }

    public TransactionWitness mergeMultisigWitness(Transaction tx, int inputIndex, TransactionWitness existingWitness,
                                                   TransactionSignature newSignature, ECKey signingKey,
                                                   Script witnessScript, Coin value, int requiredSignatures) {
        if (tx == null || existingWitness == null || newSignature == null || signingKey == null
                || witnessScript == null || value == null) {
            throw new IllegalArgumentException("merge parameters must not be null");
        }
        if (existingWitness.getPushCount() < 2) {
            throw new IllegalArgumentException("invalid P2WSH witness");
        }

        int required = requiredSignatures > 0 ? requiredSignatures : witnessScript.getNumberOfSignaturesRequiredToSpend();
        List<ECKey> pubKeys = witnessScript.getPubKeys();
        if (required < 1 || required > pubKeys.size()) {
            throw new IllegalArgumentException("invalid multisig threshold");
        }

        Map<Integer, byte[]> signaturesByKey = new TreeMap<>();
        for (int i = 1; i < existingWitness.getPushCount() - 1; i++) {
            byte[] push = existingWitness.getPush(i);
            if (push.length == 0) {
                continue;
            }
            TransactionSignature signature = decodeSignature(push);
            int pubKeyIndex = findSigningPubKey(tx, inputIndex, witnessScript, value, pubKeys, signature);
            if (pubKeyIndex >= 0) {
                signaturesByKey.putIfAbsent(pubKeyIndex, push);
            }
        }

        int newKeyIndex = findPubKeyIndex(pubKeys, signingKey);
        if (newKeyIndex < 0) {
            throw new IllegalArgumentException("signing key is not part of witnessScript");
        }
        if (!verifies(tx, inputIndex, witnessScript, value, signingKey, newSignature)) {
            throw new IllegalArgumentException("new witness signature does not verify for signing key");
        }
        if (signaturesByKey.containsKey(newKeyIndex)) {
            throw new IllegalArgumentException("duplicate signing key for P2WSH multisig input");
        }
        signaturesByKey.put(newKeyIndex, newSignature.encodeToBitcoin());

        List<byte[]> pushes = new ArrayList<>(required + 2);
        pushes.add(EMPTY);
        int added = 0;
        for (byte[] signature : signaturesByKey.values()) {
            if (added == required) {
                break;
            }
            pushes.add(signature);
            added++;
        }
        pushes.add(witnessScript.program());
        return TransactionWitness.of(pushes);
    }

    public Script extractWitnessScript(TransactionWitness witness) {
        if (witness == null || witness.getPushCount() < 2) {
            return null;
        }
        byte[] program = witness.getPush(witness.getPushCount() - 1);
        if (program.length == 0) {
            return null;
        }
        try {
            return new Script(program);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int countSignatures(TransactionWitness witness) {
        if (witness == null || witness.getPushCount() < 2) {
            return 0;
        }
        int count = 0;
        for (int i = 1; i < witness.getPushCount() - 1; i++) {
            if (witness.getPush(i).length > 0) {
                count++;
            }
        }
        return count;
    }

    public static byte[] encodeSignatureForWitness(TransactionSignature signature) {
        if (signature == null) {
            throw new IllegalArgumentException("signature must not be null");
        }
        return signature.encodeToBitcoin();
    }

    private static TransactionSignature decodeSignature(byte[] signatureBytes) {
        try {
            return TransactionSignature.decodeFromBitcoin(signatureBytes, false, false);
        } catch (SignatureDecodeException e) {
            throw new IllegalArgumentException("invalid witness signature", e);
        }
    }

    private static int findPubKeyIndex(List<ECKey> pubKeys, ECKey signingKey) {
        for (int i = 0; i < pubKeys.size(); i++) {
            if (Arrays.equals(pubKeys.get(i).getPubKey(), signingKey.getPubKey())) {
                return i;
            }
        }
        return -1;
    }

    private static int findSigningPubKey(Transaction tx, int inputIndex, Script witnessScript, Coin value,
                                         List<ECKey> pubKeys, TransactionSignature signature) {
        for (int i = 0; i < pubKeys.size(); i++) {
            if (verifies(tx, inputIndex, witnessScript, value, pubKeys.get(i), signature)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean verifies(Transaction tx, int inputIndex, Script witnessScript, Coin value,
                                    ECKey pubKey, TransactionSignature signature) {
        Sha256Hash hash = tx.hashForWitnessSignature(inputIndex, witnessScript, value,
                signature.sigHashMode(), signature.anyoneCanPay());
        return pubKey.verify(hash, signature);
    }
}
