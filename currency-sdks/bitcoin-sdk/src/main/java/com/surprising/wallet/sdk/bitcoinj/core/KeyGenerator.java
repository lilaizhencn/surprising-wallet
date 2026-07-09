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

public class KeyGenerator {

    private final ECKey ecKey;
    private final boolean compressed;

    public KeyGenerator() {
        this(new ECKey(), true);
    }

    public KeyGenerator(ECKey eckey, boolean compressed) {
        if (eckey == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.ecKey = eckey;
        this.compressed = compressed;
    }

    public static KeyGenerator fromPrivkeyWif(String keyWif) {
        try {
            DumpedPrivateKey dumpedPrivateKey = DumpedPrivateKey.fromBase58((Network) null, keyWif);
            return new KeyGenerator(dumpedPrivateKey.getKey(), dumpedPrivateKey.isPubKeyCompressed());
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("invalid WIF private key", e);
        }
    }

    public String getPrivKeyWif(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        ECKey key = compressed == ecKey.isCompressed() ? ecKey : ECKey.fromPrivate(ecKey.getPrivKey(), compressed);
        return key.getPrivateKeyEncoded(params).toBase58();
    }

    public Address getAddress(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        return LegacyAddress.fromPubKeyHash(params, ecKey.getPubKeyHash());
    }

    public String getAddressStr(NetworkParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        return Tools.byteToString((byte) params.getAddressHeader(), ecKey.getPubKeyHash());
    }

    public String getPubkeyHex() {
        return ByteUtils.formatHex(ecKey.getPubKey());
    }

    public ECKey getEcKey() {
        return ecKey;
    }

    public boolean isCompressed() {
        return compressed;
    }
}
