package com.surprising.wallet.sdk.bitcoinj.core;

import com.surprising.wallet.sdk.bitcoinj.crypto.DigestHash;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Utils;
import org.bitcoinj.base.Network;
import org.bouncycastle.util.Arrays;

/**
 * 用于生成、解析私钥的公共类。
 */
public class KeyGenerator {

    // 公、私钥对。

    private ECKey ecKey;

    // 是否压缩格式。

    private boolean compressed;

    /**
     * 随机生成一个新的私钥对。
     */
    public KeyGenerator() {
        ecKey = new ECKey();
        compressed = true;
    }

    /**
     * 逐个设定参数获取一个新对象。
     *
     * @param eckey：
     * @param compressed：
     */
    public KeyGenerator(ECKey eckey, boolean compressed) {
        ecKey = eckey;
        this.compressed = compressed;
    }

    /**
     * 从一个WIF格式的私钥解析出对应的数据。
     *
     * @param keyWif
     * @return
     */
    public static KeyGenerator fromPrivkeyWif(String keyWif) {
        byte[] key_arr;
        try {
            key_arr = Base58.decode(keyWif);
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("Base58解码失败，错误的私钥字符串！");
        }
        int len = key_arr.length;

        if (!Tools.check(key_arr)) {
            throw new IllegalArgumentException("非法的私钥字符串，校验位错误！");
        }

        boolean compressed = true;
        byte[] data;
        if (key_arr.length == 33 + 1 + 4 && key_arr[33] == (byte) 1) {
            data = Arrays.copyOfRange(key_arr, 1, len - 5);
            compressed = true;
        } else {
            data = Arrays.copyOfRange(key_arr, 1, len - 4);
            compressed = false;
        }

        int a = ((int) data[0]) & 0x80;
        if (a > 0) {
            byte[] temp = new byte[data.length + 1];
            temp[0] = (byte) 0;

            System.arraycopy(data, 0, temp, 1, data.length);
            data = temp;
        }

        ECKey ecKey = ECKey.fromPrivate(data, compressed);
        return new KeyGenerator(ecKey, compressed);
    }

    /**
     * 根据给定的网络类型，获取WIF格式的私钥。
     *
     * @return
     */
    public String getPrivKeyWif(Network params) {
        byte[] bytes;
        byte[] privByteArr = Utils.bigIntegerToBytes(ecKey.getPrivKey(), 32);
        if (compressed) {
            bytes = new byte[33];
            System.arraycopy(privByteArr, 0, bytes, 0, 32);
            bytes[32] = 1;
        } else {
            bytes = privByteArr;
        }

        return Tools.byteToString((byte) params.getDumpedPrivateKeyHeader(), bytes);
    }

    /**
     * 根据给定的网络类型，获取对应的地址。
     *
     * @param params
     * @return
     */
    public Address getAddress(Network params) {
        byte[] pub_arr = ecKey.getPubKeyHash();
        return LegacyAddress.fromPubKeyHash(params, pub_arr);
    }

    /**
     * 根据给定的网络类型，获取对应的地址。
     *
     * @param params
     * @return
     */
    public String getAddressStr(Network params) {
        byte[] pub_arr = ecKey.getPubKeyHash();
        return Tools.byteToString((byte) params.getAddressHeader(), pub_arr);
    }

    /**
     * 获取16进制编码的公钥字符串。
     *
     * @return
     */
    public String getPubkeyHex() {
        return Utils.HEX.encode(ecKey.getPubKey());
    }

    public ECKey getEcKey() {
        return ecKey;
    }

    public boolean isCompressed() {
        return compressed;
    }
}
