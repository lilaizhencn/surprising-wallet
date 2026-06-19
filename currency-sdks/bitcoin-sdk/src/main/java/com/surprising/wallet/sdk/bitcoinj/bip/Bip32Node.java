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

public class Bip32Node {
    public static final int TYPE_BITCOIN = 0;
    public static final int TYPE_LITECOIN = 1;

    private static final byte[] BIP_SEED = "Bitcoin seed".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BIT_MAIN_PRIV = {(byte) 0x04, (byte) 0x88, (byte) 0xAD, (byte) 0xE4};
    private static final byte[] BIT_MAIN_PUB = {(byte) 0x04, (byte) 0x88, (byte) 0xB2, (byte) 0x1E};
    private static final byte[] BIT_TEST_PRIV = {(byte) 0x04, (byte) 0x35, (byte) 0x83, (byte) 0x94};
    private static final byte[] BIT_TEST_PUB = {(byte) 0x04, (byte) 0x35, (byte) 0x87, (byte) 0xCF};
    private static final byte[] LITE_MAIN_PRIV = {(byte) 0x01, (byte) 0x9D, (byte) 0x9C, (byte) 0xFE};
    private static final byte[] LITE_MAIN_PUB = {(byte) 0x01, (byte) 0x9D, (byte) 0xA4, (byte) 0x62};
    private static final byte[] LITE_TEST_PRIV = {(byte) 0x04, (byte) 0x36, (byte) 0xEF, (byte) 0x7D};
    private static final byte[] LITE_TEST_PUB = {(byte) 0x04, (byte) 0x36, (byte) 0xF6, (byte) 0xE1};
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

    private final ECKey ecKey;
    private final byte[] chainCode;
    private final int depth;
    private final int parent;
    private final int sequence;

    public Bip32Node(ECKey ecKey, byte[] chainCode) {
        this(ecKey, chainCode, 0, 0, 0);
    }

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

    public Bip32Node getChild(int sequence) {
        return getChildNode(this, sequence);
    }

    public Bip32Node getChildH(int sequence) {
        return getChildNode(this, getHSeq(sequence));
    }

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

    public int fingerprint() {
        byte[] encoded = DigestHash.sha256hash160(ecKey.getPubKey());
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result <<= 8;
            result |= encoded[i] & 0xff;
        }
        return result;
    }

    public String privSerialize(int coinType, boolean isMainNet) {
        if (!ecKey.hasPrivKey()) {
            throw new IllegalStateException("node does not contain a private key");
        }
        return serialize(coinType, isMainNet, true);
    }

    public String pubSerialize(int coinType, boolean isMainNet) {
        return serialize(coinType, isMainNet, false);
    }

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

    private static String getHeaderKey(int coinType, boolean isMainNet, boolean isPrivate) {
        String coin = coinType == TYPE_BITCOIN ? "BIT" : "LITE";
        return coin + "_" + (isMainNet ? "MAIN" : "TEST") + "_" + (isPrivate ? "PRIV" : "PUB");
    }

    public String getAddress(NetworkParameters params) {
        return Tools.ecKeyToAddress(ecKey, params);
    }

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

    private static boolean isPrivateHeader(byte[] header) {
        return Arrays.areEqual(header, BIT_MAIN_PRIV) || Arrays.areEqual(header, BIT_TEST_PRIV)
                || Arrays.areEqual(header, LITE_MAIN_PRIV) || Arrays.areEqual(header, LITE_TEST_PRIV);
    }

    private static boolean isPublicHeader(byte[] header) {
        return Arrays.areEqual(header, BIT_MAIN_PUB) || Arrays.areEqual(header, BIT_TEST_PUB)
                || Arrays.areEqual(header, LITE_MAIN_PUB) || Arrays.areEqual(header, LITE_TEST_PUB);
    }

    public static int getHSeq(int sequence) {
        if (sequence < 0 || sequence >= 0x80000000L) {
            throw new IllegalArgumentException("invalid hardened child index");
        }
        return sequence | 0x80000000;
    }

    private static void writeInt32BE(int value, byte[] out, int offset) {
        out[offset] = (byte) ((value >>> 24) & 0xff);
        out[offset + 1] = (byte) ((value >>> 16) & 0xff);
        out[offset + 2] = (byte) ((value >>> 8) & 0xff);
        out[offset + 3] = (byte) (value & 0xff);
    }

    private static int readInt32BE(byte[] in, int offset) {
        return ((in[offset] & 0xff) << 24)
                | ((in[offset + 1] & 0xff) << 16)
                | ((in[offset + 2] & 0xff) << 8)
                | (in[offset + 3] & 0xff);
    }

    public ECKey getEcKey() {
        return ecKey;
    }

    public byte[] getChainCode() {
        return Arrays.copyOf(chainCode, chainCode.length);
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
