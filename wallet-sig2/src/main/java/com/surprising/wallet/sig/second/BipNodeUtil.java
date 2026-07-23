package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;

/**
 * @author atomex
 */

public class BipNodeUtil {
    private static volatile Bip32Node NODE;

    public static void initialize(Bip32Node node) {
        if (node == null || !node.getEcKey().hasPrivKey()) {
            throw new IllegalArgumentException("sig2 private BIP32 root is required");
        }
        NODE = node;
    }

    public static Bip32Node getBipNODE(Address address, AssetRuntimeMetadata currency) {
        Bip32Node node = requireRoot().getChild(44)
                .getChild(currency.getDerivationCoinType())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
        return node;
    }

    public static Bip32Node getMainBipNODE() {
        return requireRoot();
    }

    private static Bip32Node requireRoot() {
        Bip32Node node = NODE;
        if (node == null) {
            throw new IllegalStateException("sig2 key material is not initialized");
        }
        return node;
    }
}
