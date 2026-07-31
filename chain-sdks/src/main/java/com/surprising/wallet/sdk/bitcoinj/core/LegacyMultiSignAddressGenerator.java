package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.internal.CryptoUtils;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public final class LegacyMultiSignAddressGenerator {
    /**
     * 定义 {@code HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final HexFormat HEX = HexFormat.of();
    /**
     * 保存 {@code keys}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final List<ECKey> keys = new ArrayList<>();
    /**
     * 保存 {@code redeemScript}，用于承载当前对象的运行配置或业务数据。
     */
    private Script redeemScript;

    /**
     * 添加 {@code addECKey} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public void addECKey(ECKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        keys.add(key);
        redeemScript = null;
    }

    /**
     * 构建或生成 {@code generateAddress} 对应的结果，并执行输入和状态校验。
     */
    public String generateAddress(NetworkParameters params, int requiredSignatures) {
        if (params == null || requiredSignatures <= 0 || requiredSignatures > keys.size()) {
            throw new IllegalArgumentException("invalid multisig parameters");
        }
        redeemScript = ScriptBuilder.createRedeemScript(requiredSignatures, keys);
        return LegacyAddress.fromScriptHash(
                params, CryptoUtils.sha256hash160(redeemScript.program())).toBase58();
    }

    /**
     * 获取或查询 {@code getRedeemScript} 对应的数据，供调用方读取当前状态。
     */
    public Script getRedeemScript() {
        return redeemScript;
    }

    /**
     * 获取或查询 {@code getRedeemScriptHex} 对应的数据，供调用方读取当前状态。
     */
    public String getRedeemScriptHex() {
        if (redeemScript == null) {
            throw new IllegalStateException("address has not been generated");
        }
        return HEX.formatHex(redeemScript.program());
    }
}
