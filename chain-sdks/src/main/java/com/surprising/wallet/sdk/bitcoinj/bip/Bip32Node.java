package com.surprising.wallet.sdk.bitcoinj.bip;

import com.surprising.wallet.sdk.bitcoinj.crypto.DigestHash;
import com.surprising.wallet.sdk.bitcoinj.util.Tools;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.Arrays;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * BIP32（Bitcoin Improvement Proposal 32）分层确定性钱包（HD Wallet）节点实现。
 * 基于secp256k1椭圆曲线，支持从主种子派生任意深度的父子密钥对，是构建
 * 确定性钱包地址体系的核心组件。
 *
 * <h3>BIP32核心概念</h3>
 * <ul>
 *   <li><b>主密钥（Master Key）</b>：通过HMAC-SHA512从种子派生，种子通常由BIP39助记词生成</li>
 *   <li><b>扩展密钥</b>：包含密钥本身（私钥/公钥）+ 链码（chain code），用于子密钥派生</li>
 *   <li><b>硬化派生（Hardened Derivation）</b>：使用私钥+链码派生，提供更强的安全性，
 *       索引范围为 {@code 2^31 ~ 2^32-1}（即 {@code 0x80000000} 以上）</li>
 *   <li><b>非硬化派生（Non-Hardened Derivation）</b>：使用公钥+链码派生，允许仅持有公钥
 *       的情况下派生子公钥</li>
 *   <li><b>指纹（Fingerprint）</b>：父公钥的RIPEMD160(SHA-256(pubKey))前4字节</li>
 * </ul>
 *
 * <h3>支持的币种</h3>
 * 支持Bitcoin（主网/测试网）和Litecoin（主网/测试网），每种网络各有独立的私钥/公钥
 * 扩展密钥前缀（xprv/xpub等），通过{@link #TYPE_BITCOIN}和{@link #TYPE_LITECOIN}区分。
 *
 * <h3>序列化格式</h3>
 * 扩展密钥序列化为78字节的Base58Check编码字符串（如xprv、xpub），包含版本前缀、
 * 深度、父指纹、序号、链码和密钥数据。
 */
public class Bip32Node {
    /**
     * 定义 {@code TYPE_BITCOIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final int TYPE_BITCOIN = 0;
    /**
     * 定义 {@code TYPE_LITECOIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final int TYPE_LITECOIN = 1;

    /**
     * 定义 {@code BIP_SEED} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] BIP_SEED = "Bitcoin seed".getBytes(StandardCharsets.US_ASCII);
    /**
     * 定义 {@code BIT_MAIN_PRIV} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] BIT_MAIN_PRIV = {(byte) 0x04, (byte) 0x88, (byte) 0xAD, (byte) 0xE4};
    /**
     * 定义 {@code BIT_MAIN_PUB} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] BIT_MAIN_PUB = {(byte) 0x04, (byte) 0x88, (byte) 0xB2, (byte) 0x1E};
    /**
     * 定义 {@code BIT_TEST_PRIV} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] BIT_TEST_PRIV = {(byte) 0x04, (byte) 0x35, (byte) 0x83, (byte) 0x94};
    /**
     * 定义 {@code BIT_TEST_PUB} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] BIT_TEST_PUB = {(byte) 0x04, (byte) 0x35, (byte) 0x87, (byte) 0xCF};
    /**
     * 定义 {@code LITE_MAIN_PRIV} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] LITE_MAIN_PRIV = {(byte) 0x01, (byte) 0x9D, (byte) 0x9C, (byte) 0xFE};
    /**
     * 定义 {@code LITE_MAIN_PUB} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] LITE_MAIN_PUB = {(byte) 0x01, (byte) 0x9D, (byte) 0xA4, (byte) 0x62};
    /**
     * 定义 {@code LITE_TEST_PRIV} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] LITE_TEST_PRIV = {(byte) 0x04, (byte) 0x36, (byte) 0xEF, (byte) 0x7D};
    /**
     * 定义 {@code LITE_TEST_PUB} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final byte[] LITE_TEST_PUB = {(byte) 0x04, (byte) 0x36, (byte) 0xF6, (byte) 0xE1};
    /**
     * 定义 {@code HEADERS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Map<String, byte[]> HEADERS = new HashMap<>();

    static {
        HEADERS.put("BIT_MAIN_PRIV", BIT_MAIN_PRIV);
        HEADERS.put("BIT_MAIN_PUB", BIT_MAIN_PUB);
        HEADERS.put("BIT_TEST_PRIV", BIT_TEST_PRIV);
        HEADERS.put("BIT_TEST_PUB", BIT_TEST_PUB);
        HEADERS.put("LITE_MAIN_PRIV", LITE_MAIN_PRIV);
        HEADERS.put("LITE_MAIN_PUB", LITE_MAIN_PUB);
        HEADERS.put("LITE_TEST_PRIV", LITE_TEST_PRIV);
        HEADERS.put("LITE_TEST_PUB", LITE_TEST_PUB);
    }

    /**
     * 保存 {@code ecKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final ECKey ecKey;
    /**
     * 保存 {@code chainCode}，表示链、网络、资产或代币配置。
     */
    private final byte[] chainCode;
    /**
     * 保存 {@code depth}，用于承载当前对象的运行配置或业务数据。
     */
    private final int depth;
    /**
     * 保存 {@code parent}，用于承载当前对象的运行配置或业务数据。
     */
    private final int parent;
    /**
     * 保存 {@code sequence}，用于承载当前对象的运行配置或业务数据。
     */
    private final int sequence;

    /**
     * 构造 {@code Bip32Node}，初始化该组件运行所需的状态和依赖。
     */
    public Bip32Node(ECKey ecKey, byte[] chainCode) {
        this(ecKey, chainCode, 0, 0, 0);
    }

    /**
     * 构造 {@code Bip32Node}，初始化该组件运行所需的状态和依赖。
     */
    public Bip32Node(ECKey ecKey, byte[] chainCode, int depth, int parent, int sequence) {
        if (ecKey == null || chainCode == null || chainCode.length != 32) {
            throw new IllegalArgumentException("key and 32-byte chain code are required");
        }
        this.ecKey = ecKey;
        this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
        this.depth = depth;
        this.parent = parent;
        this.sequence = sequence;
    }

    /**
     * 获取或查询 {@code getMasterKey} 对应的数据，供调用方读取当前状态。
     */
    public static Bip32Node getMasterKey(byte[] seed) {
        byte[] result = Tools.hmacSha512(seed, BIP_SEED);
        byte[] left = Arrays.copyOfRange(result, 0, 32);
        byte[] right = Arrays.copyOfRange(result, 32, 64);
        BigInteger key = new BigInteger(1, left);
        BigInteger curveOrder = ECKey.ecDomainParameters().getN();
        if (key.signum() == 0 || key.compareTo(curveOrder) >= 0) {
            throw new IllegalStateException("invalid master key material");
        }
        return new Bip32Node(ECKey.fromPrivate(key, true), right, 0, 0, 0);
    }

    /**
     * 获取或查询 {@code getChild} 对应的数据，供调用方读取当前状态。
     */
    public Bip32Node getChild(int sequence) {
        return getChildNode(this, sequence);
    }

    /**
     * 获取或查询 {@code getChildH} 对应的数据，供调用方读取当前状态。
     */
    public Bip32Node getChildH(int sequence) {
        return getChildNode(this, getHSeq(sequence));
    }

    /**
     * 获取或查询 {@code getChildNode} 对应的数据，供调用方读取当前状态。
     */
    public static Bip32Node getChildNode(Bip32Node node, int sequence) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        ECKey parentKey = node.getEcKey();
        boolean hardened = (sequence & 0x80000000) != 0;
        if (hardened && !parentKey.hasPrivKey()) {
            throw new IllegalArgumentException("public-only nodes cannot derive hardened children");
        }

        byte[] data;
        if (hardened) {
            byte[] privateKey = parentKey.getPrivKeyBytes();
            data = new byte[1 + privateKey.length + 4];
            System.arraycopy(privateKey, 0, data, 1, privateKey.length);
            writeInt32BE(sequence, data, 1 + privateKey.length);
        } else {
            byte[] pubKey = parentKey.getPubKey();
            data = new byte[pubKey.length + 4];
            System.arraycopy(pubKey, 0, data, 0, pubKey.length);
            writeInt32BE(sequence, data, pubKey.length);
        }

        byte[] result = Tools.hmacSha512(data, node.getChainCode());
        byte[] left = Arrays.copyOfRange(result, 0, 32);
        byte[] right = Arrays.copyOfRange(result, 32, 64);
        BigInteger tweak = new BigInteger(1, left);
        BigInteger curveOrder = ECKey.ecDomainParameters().getN();
        if (tweak.compareTo(curveOrder) >= 0) {
            throw new IllegalStateException("invalid child key material");
        }

        if (parentKey.hasPrivKey()) {
            BigInteger childPrivate = tweak.add(parentKey.getPrivKey()).mod(curveOrder);
            if (childPrivate.signum() == 0) {
                throw new IllegalStateException("invalid child private key");
            }
            return new Bip32Node(ECKey.fromPrivate(childPrivate, true), right,
                    node.getDepth() + 1, node.fingerprint(), sequence);
        }

        ECPoint point = ECKey.ecDomainParameters().getG().multiply(tweak).add(parentKey.getPubKeyPoint()).normalize();
        if (point.isInfinity()) {
            throw new IllegalStateException("invalid child public key");
        }
        return new Bip32Node(ECKey.fromPublicOnly(point, true), right,
                node.getDepth() + 1, node.fingerprint(), sequence);
    }

    /**
     * 执行 {@code fingerprint} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int fingerprint() {
        byte[] encoded = DigestHash.sha256hash160(ecKey.getPubKey());
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result <<= 8;
            result |= encoded[i] & 0xff;
        }
        return result;
    }

    /**
     * 执行 {@code privSerialize} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String privSerialize(int coinType, boolean isMainNet) {
        if (!ecKey.hasPrivKey()) {
            throw new IllegalStateException("node does not contain a private key");
        }
        return serialize(coinType, isMainNet, true);
    }

    /**
     * 执行 {@code pubSerialize} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String pubSerialize(int coinType, boolean isMainNet) {
        return serialize(coinType, isMainNet, false);
    }

    /**
     * 编码或序列化 {@code serialize} 对应的数据，生成链上或接口需要的表示。
     */
    private String serialize(int coinType, boolean isMainNet, boolean isPrivate) {
        byte[] result = new byte[78];
        int pos = 0;
        byte[] head = HEADERS.get(getHeaderKey(coinType, isMainNet, isPrivate));
        if (head == null) {
            throw new IllegalArgumentException("unsupported coin type");
        }
        System.arraycopy(head, 0, result, pos, 4);
        pos += 4;
        result[pos++] = (byte) (depth & 0xff);
        writeInt32BE(parent, result, pos);
        pos += 4;
        writeInt32BE(sequence, result, pos);
        pos += 4;
        System.arraycopy(chainCode, 0, result, pos, 32);
        pos += 32;
        if (isPrivate) {
            result[pos++] = 0x00;
            System.arraycopy(ecKey.getPrivKeyBytes(), 0, result, pos, 32);
        } else {
            System.arraycopy(ecKey.getPubKey(), 0, result, pos, 33);
        }
        return Tools.byteToString(result);
    }

    /**
     * 获取或查询 {@code getHeaderKey} 对应的数据，供调用方读取当前状态。
     */
    private static String getHeaderKey(int coinType, boolean isMainNet, boolean isPrivate) {
        String coin = coinType == TYPE_BITCOIN ? "BIT" : "LITE";
        return coin + "_" + (isMainNet ? "MAIN" : "TEST") + "_" + (isPrivate ? "PRIV" : "PUB");
    }

    /**
     * 获取或查询 {@code getAddress} 对应的数据，供调用方读取当前状态。
     */
    public String getAddress(NetworkParameters params) {
        return Tools.ecKeyToAddress(ecKey, params);
    }

    /**
     * 解析或转换 {@code decode} 对应的数据，并校验其格式和边界。
     */
    public static Bip32Node decode(String serialized) {
        try {
            byte[] data = Base58.decodeChecked(serialized);
            if (data.length != 78) {
                throw new IllegalArgumentException("invalid extended key length");
            }

            int pos = 4;
            byte[] header = Arrays.copyOfRange(data, 0, pos);
            boolean isPrivate = isPrivateHeader(header);
            if (!isPrivate && !isPublicHeader(header)) {
                throw new IllegalArgumentException("invalid extended key header");
            }

            int depth = data[pos++] & 0xff;
            int parent = readInt32BE(data, pos);
            pos += 4;
            int sequence = readInt32BE(data, pos);
            pos += 4;
            byte[] chainCode = Arrays.copyOfRange(data, pos, pos + 32);
            pos += 32;
            byte[] keyData = Arrays.copyOfRange(data, pos, data.length);

            ECKey key;
            if (isPrivate) {
                if (keyData.length != 33 || keyData[0] != 0) {
                    throw new IllegalArgumentException("invalid extended private key data");
                }
                key = ECKey.fromPrivate(Arrays.copyOfRange(keyData, 1, 33), true);
            } else {
                key = ECKey.fromPublicOnly(keyData);
            }
            return new Bip32Node(key, chainCode, depth, parent, sequence);
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("invalid extended key", e);
        }
    }

    /**
     * 判断 {@code isPrivateHeader} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean isPrivateHeader(byte[] header) {
        return Arrays.areEqual(header, BIT_MAIN_PRIV) || Arrays.areEqual(header, BIT_TEST_PRIV)
                || Arrays.areEqual(header, LITE_MAIN_PRIV) || Arrays.areEqual(header, LITE_TEST_PRIV);
    }

    /**
     * 判断 {@code isPublicHeader} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean isPublicHeader(byte[] header) {
        return Arrays.areEqual(header, BIT_MAIN_PUB) || Arrays.areEqual(header, BIT_TEST_PUB)
                || Arrays.areEqual(header, LITE_MAIN_PUB) || Arrays.areEqual(header, LITE_TEST_PUB);
    }

    /**
     * 获取或查询 {@code getHSeq} 对应的数据，供调用方读取当前状态。
     */
    public static int getHSeq(int sequence) {
        if (sequence < 0 || sequence >= 0x80000000L) {
            throw new IllegalArgumentException("invalid hardened child index");
        }
        return sequence | 0x80000000;
    }

    /**
     * 执行 {@code writeInt32BE} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static void writeInt32BE(int value, byte[] out, int offset) {
        out[offset] = (byte) ((value >>> 24) & 0xff);
        out[offset + 1] = (byte) ((value >>> 16) & 0xff);
        out[offset + 2] = (byte) ((value >>> 8) & 0xff);
        out[offset + 3] = (byte) (value & 0xff);
    }

    /**
     * 获取或查询 {@code readInt32BE} 对应的数据，供调用方读取当前状态。
     */
    private static int readInt32BE(byte[] in, int offset) {
        return ((in[offset] & 0xff) << 24)
                | ((in[offset + 1] & 0xff) << 16)
                | ((in[offset + 2] & 0xff) << 8)
                | (in[offset + 3] & 0xff);
    }

    /**
     * 获取或查询 {@code getEcKey} 对应的数据，供调用方读取当前状态。
     */
    public ECKey getEcKey() {
        return ecKey;
    }

    /**
     * 获取或查询 {@code getChainCode} 对应的数据，供调用方读取当前状态。
     */
    public byte[] getChainCode() {
        return Arrays.copyOf(chainCode, chainCode.length);
    }

    /**
     * 获取或查询 {@code getDepth} 对应的数据，供调用方读取当前状态。
     */
    public int getDepth() {
        return depth;
    }

    /**
     * 获取或查询 {@code getParent} 对应的数据，供调用方读取当前状态。
     */
    public int getParent() {
        return parent;
    }

    /**
     * 获取或查询 {@code getSequence} 对应的数据，供调用方读取当前状态。
     */
    public int getSequence() {
        return sequence;
    }
}
