package com.surprising.wallet.sdk.bitcoincashj.bip;

import com.surprising.wallet.sdk.bitcoincashj.crypto.DigestHash;
import com.surprising.wallet.sdk.bitcoincashj.util.Tools;
import org.bitcoincashj.core.AddressFormatException;
import org.bitcoincashj.core.Base58;
import org.bitcoincashj.core.ECKey;
import org.bitcoincashj.core.NetworkParameters;
import org.spongycastle.math.ec.ECPoint;
import org.spongycastle.util.Arrays;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * BIP32对应的实现： https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki
 * 
 * 分层确定性钱包规范。
 */
public class Bip32Node {

    /** 比特币 */
    public static final int TYPE_BITCOIN = 0;
    /** 莱特币 */
    public static final int TYPE_LITECOIN = 1;

    /** hmacSha512中使用的种子。 */
    private static final byte[] BIP_SEED = "Bitcoin seed".getBytes();

    /** 序列化用到的数据。 */
    /** 比特币正式网络，使用私钥。 */
    private static final byte[] BIT_MAIN_PRIV =
            new byte[] {(byte) 0X04, (byte) 0X88, (byte) 0XAD, (byte) 0XE4};
    /** 比特币正式网络，使用公钥。 */
    private static final byte[] BIT_MAIN_PUB =
            new byte[] {(byte) 0X04, (byte) 0X88, (byte) 0XB2, (byte) 0X1E};

    /** 比特币测试网络，使用私钥。 */
    private static final byte[] BIT_TEST_PRIV =
            new byte[] {(byte) 0X04, (byte) 0X35, (byte) 0X83, (byte) 0X94};
    /** 比特币测试网络，使用公钥。 */
    private static final byte[] BIT_TEST_PUB =
            new byte[] {(byte) 0X04, (byte) 0X35, (byte) 0X87, (byte) 0XCF};

    /** 莱特币正式网络，使用私钥。 */
    private static final byte[] LITE_MAIN_PRIV =
            new byte[] {(byte) 0X01, (byte) 0X9D, (byte) 0X9C, (byte) 0XFE};
    /** 莱特币正式网络，使用公钥。 */
    private static final byte[] LITE_MAIN_PUB =
            new byte[] {(byte) 0X01, (byte) 0X9D, (byte) 0XA4, (byte) 0X62};

    /** 莱特币测试网络，使用私钥。 */
    private static final byte[] LITE_TEST_PRIV =
            new byte[] {(byte) 0X04, (byte) 0X36, (byte) 0XEF, (byte) 0X7D};
    /** 莱特币测试网络，使用公钥。 */
    private static final byte[] LITE_TEST_PUB =
            new byte[] {(byte) 0X04, (byte) 0X36, (byte) 0XF6, (byte) 0XE1};

    /** 名称和字节数组对应关系。 */
    private static final Map<String, byte[]> byteMap = new HashMap<String, byte[]>();
    static {
        byteMap.put("BIT_MAIN_PRIV", BIT_MAIN_PRIV);
        byteMap.put("BIT_MAIN_PUB", BIT_MAIN_PUB);

        byteMap.put("BIT_TEST_PRIV", BIT_TEST_PRIV);
        byteMap.put("BIT_TEST_PUB", BIT_TEST_PUB);

        byteMap.put("LITE_MAIN_PRIV", LITE_MAIN_PRIV);
        byteMap.put("LITE_MAIN_PUB", LITE_MAIN_PUB);

        byteMap.put("LITE_TEST_PRIV", LITE_TEST_PRIV);
        byteMap.put("LITE_TEST_PUB", LITE_TEST_PUB);
    }

    private ECKey ecKey;

    private byte[] chainCode;

    private int depth;

    private int parent;

    private int sequence;

    /**
     * 根据给定参数创建设置一个Master节点。chainCode长度要求为32位。
     */
    public Bip32Node(ECKey ecKey, byte[] chainCode) {
        super();

        if (ecKey == null || chainCode == null || chainCode.length == 0) {
            throw new IllegalArgumentException("密钥和“chain code”不能为空！");
        }
        if(chainCode.length != 32){
            throw new IllegalArgumentException("“chain code”长度必须是32位！");
        }

        this.ecKey = ecKey;
        this.chainCode = chainCode;
        this.depth = 0;
        this.parent = 0;
        this.sequence = 0;
    }

    /**
     * 根据给定参数创建设置一个节点。
     * 
     * @param ecKey
     * @param chainCode
     * @param depth
     * @param parent
     * @param sequence
     */
    public Bip32Node(ECKey ecKey, byte[] chainCode, int depth, int parent, int sequence) {
        super();

        if (ecKey == null || chainCode == null || chainCode.length == 0) {
            throw new IllegalArgumentException("密钥和“chain code”不能为空！");
        }

        this.ecKey = ecKey;
        this.chainCode = chainCode;
        this.depth = depth;
        this.parent = parent;
        this.sequence = sequence;
    }

    /**
     * 根据给定的种子生成一个Master节点。
     * 
     * @param seed
     * @return
     */
    public static Bip32Node getMasterKey(byte[] seed) {
        byte[] result = Tools.hmacSha512(seed, BIP_SEED);
        if (result == null || result.length != 64) {
            throw new RuntimeException("seed转换后长度错误，请重试！");
        }

        byte[] left = Arrays.copyOfRange(result, 0, 32);
        byte[] right = Arrays.copyOfRange(result, 32, 64);

        BigInteger bigInt = new BigInteger(1, left);
        if (bigInt.compareTo(ECKey.CURVE.getN()) >= 0) {
            throw new RuntimeException("生成了一个不应该出现的数值！");
        }

        return new Bip32Node(ECKey.fromPrivate(bigInt, true), right, 0, 0, 0);
    }

    /**
     * 生成指定sequence位置的子节点。
     * 
     * @param sequence
     * @return
     */
    public Bip32Node getChild(int sequence) {
        return Bip32Node.getChildNode(this, sequence);
    }

    /**
     * 获取seq对应的Hard模式的子节点。
     * <p>
     * BIP32中，hard模式的值为“seq + 2<sup>31</sup>”。
     * 
     * @param sequence
     * @return
     */
    public Bip32Node getChildH(int sequence) {
        return Bip32Node.getChildNode(this, getHSeq(sequence));
    }

    /**
     * 根据指定的节点和sequence获取子节点数据。
     * 
     * @param node： 指定的父节点
     * @param sequence： 子节点对应的sequence
     * @return
     */
    @SuppressWarnings("deprecation")
    public static Bip32Node getChildNode(Bip32Node node, int sequence) {
        ECKey nodeKey = node.getEcKey();
        if (nodeKey == null || node.getChainCode() == null) {
            throw new IllegalArgumentException("密钥和“chain code”不能为空！");
        }

        int seqCheck = (sequence & 0X80000000);
        if (seqCheck != 0 && !nodeKey.hasPrivKey()) {
            throw new IllegalArgumentException("只有公钥不支持hardened模式！");
        }

        byte[] sub = null;
        byte[] pubKey = nodeKey.getPubKey();
        if (seqCheck == 0) {
            int pubLen = pubKey.length;
            sub = new byte[pubLen + 4];
            System.arraycopy(pubKey, 0, sub, 0, pubLen);
            sub[pubLen] = (byte) ((sequence >>> 24) & 0XFF);
            sub[pubLen + 1] = (byte) ((sequence >>> 16) & 0XFF);
            sub[pubLen + 2] = (byte) ((sequence >>> 8) & 0XFF);
            sub[pubLen + 3] = (byte) (sequence & 0XFF);
        } else {
            byte[] privKey = nodeKey.getPrivKeyBytes();
            int privLen = privKey.length;

            sub = new byte[privLen + 5];
            System.arraycopy(privKey, 0, sub, 1, privLen);
            sub[privLen + 1] = (byte) ((sequence >>> 24) & 0XFF);
            sub[privLen + 2] = (byte) ((sequence >>> 16) & 0XFF);
            sub[privLen + 3] = (byte) ((sequence >>> 8) & 0XFF);
            sub[privLen + 4] = (byte) (sequence & 0XFF);
        }

        byte[] result = Tools.hmacSha512(sub, node.getChainCode());
        byte[] left = Arrays.copyOfRange(result, 0, 32);
        byte[] right = Arrays.copyOfRange(result, 32, 64);

        BigInteger bigInt = new BigInteger(1, left);
        if (bigInt.compareTo(ECKey.CURVE.getN()) >= 0) {
            throw new RuntimeException("生成了一个不应该出现的数值！");
        }

        if (nodeKey.hasPrivKey()) {
            BigInteger temp = bigInt.add(new BigInteger(1, nodeKey.getPrivKeyBytes()))
                    .mod(ECKey.CURVE.getN());
            if (temp.equals(BigInteger.ZERO)) {
                throw new RuntimeException("生成了一个不应该出现的数值！");
            }

            return new Bip32Node(ECKey.fromPrivate(temp, true), right, node.getDepth() + 1,
                    node.fingerprint(), sequence);
        } else {
            ECPoint point = ECKey.CURVE.getG().multiply(bigInt)
                    .add(ECKey.CURVE.getCurve().decodePoint(pubKey));
            if (point.isInfinity()) {
                throw new RuntimeException("生成了一个不应该出现的数值！");
            }

            pubKey = new ECPoint.Fp(ECKey.CURVE.getCurve(), point.getX(), point.getY(), true)
                    .getEncoded();
            return new Bip32Node(ECKey.fromPublicOnly(pubKey), right, node.getDepth() + 1,
                    node.fingerprint(), sequence);
        }
    }

    /**
     * 合并地址的主字符串（公钥进行sha256和hash160后）的前4位（32字节）。
     * 
     * @return
     */
    public int fingerprint() {
        byte[] pubKey = this.ecKey.getPubKey();
        byte[] encoded = DigestHash.sha256hash160(pubKey);

        int result = 0;
        for (int i = 0; i < 4; i++) {
            result <<= 8;
            result |= encoded[i] & 0XFF;
        }

        return result;
    }

    /**
     * 将节点的私钥数据序列化为一个字符串。
     * <p>
     * 格式：version（4位）: 0-4； depth（1位）: 4-5； parent_fingerprint（4位）: 5-9； child_index（4位）: 9-13；
     * chain_code（32位）: 13-45； key_bytes（33位）: 45-78。
     * 
     * @param coinType： 币种：0：比特币；1：莱特币。
     * @param isMainNet： true：正式网络；false：测试网络。
     * @return
     */
    public String privSerialize(int coinType, boolean isMainNet) {
        if (this.ecKey.isPubKeyOnly()) {
            throw new RuntimeException("该节点没有私钥数据！");
        }
        return serialize(coinType, isMainNet, true);
    }

    /**
     * 将节点的公钥数据序列化为一个字符串。
     * <p>
     * 格式：version（4位）: 0-4； depth（1位）: 4-5； parent_fingerprint（4位）: 5-9； child_index（4位）: 9-13；
     * chain_code（32位）: 13-45； key_bytes（33位）: 45-78。
     * 
     * @param coinType： 币种：0：比特币；1：莱特币。
     * @param isMainNet： true：正式网络；false：测试网络。
     * @return
     */
    public String pubSerialize(int coinType, boolean isMainNet) {
        return serialize(coinType, isMainNet, false);
    }

    /**
     * 根据提供的条件获取序列化数据。
     * <p>
     * 格式：version（4位）: 0-4； depth（1位）: 4-5； parent_fingerprint（4位）: 5-9； child_index（4位）: 9-13；
     * chain_code（32位）: 13-45； key_bytes（33位）: 45-78。
     * 
     * @param coinType： 币种：0：比特币；1：莱特币。
     * @param isMainNet： true：正式网络；false：测试网络。
     * @param isPrivate： true：序列化私钥数据；false：序列化公钥数据。
     * @return
     */
    private String serialize(int coinType, boolean isMainNet, boolean isPrivate) {
        byte[] result = new byte[78];

        int pos = 0;
        String nameStr = getHeadStr(coinType, isMainNet, isPrivate);
        byte[] head = byteMap.get(nameStr);
        System.arraycopy(head, 0, result, pos, 4);

        pos += 4;

        // 4
        result[pos++] = (byte) (this.depth & 0XFF);
        int parent = this.parent;
        // 5 - 8
        result[pos++] = (byte) ((parent >>> 24) & 0XFF);
        result[pos++] = (byte) ((parent >>> 16) & 0XFF);
        result[pos++] = (byte) ((parent >>> 8) & 0XFF);
        result[pos++] = (byte) (parent & 0XFF);

        int sequence = this.sequence;
        // 9 - 12
        result[pos++] = (byte) ((sequence >>> 24) & 0XFF);
        result[pos++] = (byte) ((sequence >>> 16) & 0XFF);
        result[pos++] = (byte) ((sequence >>> 8) & 0XFF);
        result[pos++] = (byte) (sequence & 0XFF);

        System.arraycopy(this.chainCode, 0, result, 13, 32);
        pos += 32;

        if (isPrivate) {
            result[pos++] = 0X00;
            System.arraycopy(this.ecKey.getPrivKeyBytes(), 0, result, pos, 32);
        } else {
            System.arraycopy(this.ecKey.getPubKey(), 0, result, pos, 33);
        }

        return Tools.byteToString(result);
    }

    /**
     * 根据各参数转换出头部字节数组对应的字符串。
     * 
     * @param coinType
     * @param isMainNet
     * @param isPrivate
     * @return
     */
    private String getHeadStr(int coinType, boolean isMainNet, boolean isPrivate) {
        StringBuilder builder = new StringBuilder();
        switch (coinType) {
            case TYPE_BITCOIN:
                builder.append("BIT");
                break;
            case TYPE_LITECOIN:
                builder.append("LITE");
                break;
        }
        builder.append("_");
        if (isMainNet) {
            builder.append("MAIN");
        } else {
            builder.append("TEST");
        }
        builder.append("_");

        if (isPrivate) {
            builder.append("PRIV");
        } else {
            builder.append("PUB");
        }

        return builder.toString();
    }

    /**
     * 返回指定网络类型中对应的地址字符串。
     * 
     * @param params
     * @return
     */
    public String getAddress(NetworkParameters params) {
        return Tools.ecKeyToAddress(ecKey, params);
    }

    /**
     * 从一个序列化后的字符串中解析出对应的信息。
     * 
     * @param serialized
     * @return
     */
    public static Bip32Node decode(String serialized) {
        try {
            byte[] temp = Base58.decode(serialized);
            if (temp.length != 82) {
                throw new IllegalArgumentException("输入的字符串格式错误！");
            }

            if (!Tools.check(temp)) {
                throw new IllegalArgumentException("字符串检验码错误！");
            }

            byte[] data = Arrays.copyOfRange(temp, 0, 78);

            int pos = 4;
            byte[] headByte = Arrays.copyOfRange(data, 0, pos);
            boolean isPrivate = false;
            if (Arrays.areEqual(headByte, BIT_MAIN_PRIV) || Arrays.areEqual(headByte, BIT_TEST_PRIV)
                    || Arrays.areEqual(headByte, LITE_MAIN_PRIV)
                    || Arrays.areEqual(headByte, LITE_TEST_PRIV)) {
                isPrivate = true;
            } else if (Arrays.areEqual(headByte, BIT_MAIN_PUB)
                    || Arrays.areEqual(headByte, BIT_TEST_PUB)
                    || Arrays.areEqual(headByte, LITE_MAIN_PUB)
                    || Arrays.areEqual(headByte, LITE_TEST_PUB)) {
                isPrivate = false;
            } else {
                throw new IllegalArgumentException("网络字头错误！");
            }

            int depth = data[pos++] & 0XFF;

            int parent = data[pos++] & 0XFF;
            for (int i = 0; i < 3; i++) {
                parent <<= 8;
                parent |= data[pos++] & 0XFF;
            }

            int sequence = data[pos++] & 0XFF;
            for (int i = 0; i < 3; i++) {
                sequence <<= 8;
                sequence |= data[pos++] & 0XFF;
            }

            byte[] chainCode = Arrays.copyOfRange(data, pos, pos + 32);
            pos += 32;

            byte[] key = Arrays.copyOfRange(data, pos, data.length);

            ECKey ecKey = null;
            if (isPrivate) {
                ecKey = ECKey.fromPrivate(key, true);
            } else {
                ecKey = ECKey.fromPublicOnly(key);
            }

            return new Bip32Node(ecKey, chainCode, depth, parent, sequence);
        } catch (AddressFormatException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 获取seq对应的Hard模式的序号。
     * <p>
     * BIP32中，hard模式的值为“seq + 2<sup>31</sup>”，Java中Integer.MAX_VALUE为2<sup>31</sup>-1。
     * 
     * @param seq
     * @return
     */
    public static int getHSeq(int seq) {
        return Integer.MAX_VALUE + 1 + seq;
    }

    public ECKey getEcKey() {
        return ecKey;
    }

    public byte[] getChainCode() {
        return chainCode;
    }

    public int getDepth() {
        return depth;
    }

    public int getParent() {
        return parent;
    }

    public int getSequence() {
        return sequence;
    }

}
