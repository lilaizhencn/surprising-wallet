package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.signature.KeyConfig;
import org.springframework.util.ObjectUtils;

/**
 * @author atomex
 */

public class BipNodeUtil {
    private static Bip32Node NODE;

    public static Bip32Node getBipNODE(Address address, AssetRuntimeMetadata currency) {
        if (ObjectUtils.isEmpty(BipNodeUtil.NODE)) {
            String mk = KeyConfig.getValue("masterNode");
            //final String dk = SecretConfig.decryptKey(mk);
            BipNodeUtil.NODE = Bip32Node.decode(mk);
        }
        Bip32Node node = BipNodeUtil.NODE.getChild(44)
                .getChild(currency.getDerivationCoinType())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
        return node;
    }

    public static Bip32Node getMainBipNODE() {
        if (ObjectUtils.isEmpty(BipNodeUtil.NODE)) {
            String mk = KeyConfig.getValue("masterNode");
            BipNodeUtil.NODE = Bip32Node.decode(mk);
        }
        return BipNodeUtil.NODE;
    }
}
