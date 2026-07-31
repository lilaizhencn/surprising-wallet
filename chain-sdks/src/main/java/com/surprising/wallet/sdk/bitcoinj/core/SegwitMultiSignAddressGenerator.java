package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.HexFormat;

/**
 * Native SegWit（P2WSH）多签地址生成器，用于生成基于隔离见证（Segregated Witness）的
 * 多签地址。该类管理一组压缩公钥，使用{@link org.bitcoinj.script.ScriptBuilder#createMultiSigOutputScript}
 * 创建多签赎回脚本（witnessScript），并通过SHA-256哈希计算P2WSH地址。
 *
 * <p>关键概念：</p>
 * <ul>
 *   <li><b>P2WSH</b>（Pay to Witness Script Hash）：将资金锁定到赎回脚本的SHA-256哈希值</li>
 *   <li><b>witnessScript</b>：多签赎回脚本，定义了m-of-n的签名阈值</li>
 *   <li><b>witnessProgram</b>：witnessScript的SHA-256哈希，即P2WSH地址的核心部分</li>
 * </ul>
 *
 * <p>限制：最多16个公钥，所有公钥必须为压缩格式（33字节），公钥基于secp256k1椭圆曲线。</p>
 */
public class SegwitMultiSignAddressGenerator {
    /**
     * 定义 {@code HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final HexFormat HEX = HexFormat.of();

    /**
     * 保存 {@code ecKeyList}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private List<ECKey> ecKeyList = new ArrayList<>();
    /**
     * 保存 {@code witnessScript}，用于承载当前对象的运行配置或业务数据。
     */
    private Script witnessScript;
    /**
     * 保存 {@code minSignNum}，用于承载当前对象的运行配置或业务数据。
     */
    private int minSignNum;

    /**
     * 添加 {@code addECKey} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public void addECKey(ECKey key) {
        if (key == null || ecKeyList.size() >= 16) {
            throw new IllegalArgumentException("max 16 non-null public keys");
        }
        if (!key.isCompressed()) {
            throw new IllegalArgumentException("native SegWit multisig requires compressed public keys");
        }
        ecKeyList.add(key);
        witnessScript = null;
        minSignNum = 0;
    }

    /**
     * 设置或更新 {@code setECKey} 对应的状态，并保持相关业务字段一致。
     */
    public boolean setECKey(int index, ECKey key) {
        if (index < 0 || index >= ecKeyList.size() || key == null || !key.isCompressed()) {
            return false;
        }
        ecKeyList.set(index, key);
        witnessScript = null;
        minSignNum = 0;
        return true;
    }

    /**
     * 构建或生成 {@code generateAddress} 对应的结果，并执行输入和状态校验。
     */
    public String generateAddress(NetworkParameters params, int minSignNum) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        int size = ecKeyList.size();
        if (size < 2) {
            throw new IllegalArgumentException("at least two public keys are required");
        }
        if (minSignNum < 1) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        if (minSignNum > size) {
            minSignNum = size;
        }
        witnessScript = ScriptBuilder.createMultiSigOutputScript(minSignNum, ecKeyList);
        this.minSignNum = minSignNum;
        return SegwitAddress.fromHash(params, Sha256Hash.hash(witnessScript.program())).toBech32();
    }

    /**
     * 获取或查询 {@code getWitnessScript} 对应的数据，供调用方读取当前状态。
     */
    public Script getWitnessScript() {
        return witnessScript;
    }

    /**
     * 获取或查询 {@code getWitnessScriptStr} 对应的数据，供调用方读取当前状态。
     */
    public String getWitnessScriptStr() {
        return witnessScript == null ? null : HEX.formatHex(witnessScript.program());
    }

    /**
     * 获取或查询 {@code getScriptStr} 对应的数据，供调用方读取当前状态。
     */
    public String getScriptStr() {
        return getWitnessScriptStr();
    }

    /**
     * 获取或查询 {@code getWitnessProgram} 对应的数据，供调用方读取当前状态。
     */
    public byte[] getWitnessProgram() {
        return witnessScript == null ? null : Sha256Hash.hash(witnessScript.program());
    }

    /**
     * 设置或更新 {@code setEcKeyList} 对应的状态，并保持相关业务字段一致。
     */
    public void setEcKeyList(List<ECKey> ecKeyList) {
        if (ecKeyList == null) {
            return;
        }
        List<ECKey> sanitized = new ArrayList<>(ecKeyList);
        Iterator<ECKey> iter = sanitized.iterator();
        while (iter.hasNext()) {
            ECKey key = iter.next();
            if (key == null) {
                iter.remove();
            } else if (!key.isCompressed()) {
                throw new IllegalArgumentException("native SegWit multisig requires compressed public keys");
            }
        }
        this.ecKeyList = sanitized;
        witnessScript = null;
        minSignNum = 0;
    }

    /**
     * 获取或查询 {@code getEcKeyList} 对应的数据，供调用方读取当前状态。
     */
    public List<ECKey> getEcKeyList() {
        return new ArrayList<>(ecKeyList);
    }

    /**
     * 获取或查询 {@code getMinSignNum} 对应的数据，供调用方读取当前状态。
     */
    public int getMinSignNum() {
        return minSignNum;
    }

    /**
     * 获取或查询 {@code getMaxSignNum} 对应的数据，供调用方读取当前状态。
     */
    public int getMaxSignNum() {
        return ecKeyList.size();
    }
}
