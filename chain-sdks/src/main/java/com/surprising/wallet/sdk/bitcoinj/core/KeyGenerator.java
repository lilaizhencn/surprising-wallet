package com.surprising.wallet.sdk.bitcoinj.core;

import com.surprising.wallet.sdk.bitcoinj.util.Tools;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;

/**
 * Bitcoin密钥生成器，封装{@link org.bitcoinj.crypto.ECKey ECKey}（基于secp256k1椭圆曲线）提供
 * 密钥管理功能。支持从WIF格式私钥导入、导出WIF格式私钥、生成传统地址（P2PKH）以及获取
 * 公钥十六进制表示。密钥对采用ECDSA签名算法，地址通过SHA-256和RIPEMD160哈希计算得出。
 *
 * <p>主要用于多签钱包中参与方密钥的管理与地址生成。</p>
 */
public class KeyGenerator {

    /**
     * 保存 {@code ecKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final ECKey ecKey;
    /**
     * 保存 {@code compressed}，用于承载当前对象的运行配置或业务数据。
     */
    private final boolean compressed;

    /**
     * 构造 {@code KeyGenerator}，初始化该组件运行所需的状态和依赖。
     */
    public KeyGenerator() {
        this(new ECKey(), true);
    }

    /**
     * 构造 {@code KeyGenerator}，初始化该组件运行所需的状态和依赖。
     */
    public KeyGenerator(ECKey eckey, boolean compressed) {
        if (eckey == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.ecKey = eckey;
        this.compressed = compressed;
    }

    /**
     * 解析 {@code fromPrivkeyWif} 对应的输入，并转换为当前业务模型。
     */
    public static KeyGenerator fromPrivkeyWif(String keyWif) {
        try {
            DumpedPrivateKey dumpedPrivateKey = DumpedPrivateKey.fromBase58((Network) null, keyWif);
            return new KeyGenerator(dumpedPrivateKey.getKey(), dumpedPrivateKey.isPubKeyCompressed());
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("invalid WIF private key", e);
        }
    }

    /**
     * 获取或查询 {@code getPrivKeyWif} 对应的数据，供调用方读取当前状态。
     */
    public String getPrivKeyWif(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        ECKey key = compressed == ecKey.isCompressed() ? ecKey : ECKey.fromPrivate(ecKey.getPrivKey(), compressed);
        return key.getPrivateKeyEncoded(params).toBase58();
    }

    /**
     * 获取或查询 {@code getAddress} 对应的数据，供调用方读取当前状态。
     */
    public Address getAddress(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        return LegacyAddress.fromPubKeyHash(params, ecKey.getPubKeyHash());
    }

    /**
     * 获取或查询 {@code getAddressStr} 对应的数据，供调用方读取当前状态。
     */
    public String getAddressStr(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        return Tools.byteToString((byte) params.getAddressHeader(), ecKey.getPubKeyHash());
    }

    /**
     * 获取或查询 {@code getPubkeyHex} 对应的数据，供调用方读取当前状态。
     */
    public String getPubkeyHex() {
        return ByteUtils.formatHex(ecKey.getPubKey());
    }

    /**
     * 获取或查询 {@code getEcKey} 对应的数据，供调用方读取当前状态。
     */
    public ECKey getEcKey() {
        return ecKey;
    }

    /**
     * 判断 {@code isCompressed} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isCompressed() {
        return compressed;
    }
}
