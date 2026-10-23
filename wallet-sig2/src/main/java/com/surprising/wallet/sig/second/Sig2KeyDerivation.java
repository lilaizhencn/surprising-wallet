package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class Sig2KeyDerivation {
    private final WalletKeyMaterialProvider keys;
    public Sig2KeyDerivation(@Qualifier("sig2KeyMaterial") WalletKeyMaterialProvider keys) { this.keys = keys; }
    public Bip32Node derive(UtxoSigningRequest.Key key, UtxoSigningRequest.Asset asset) {
        return keys.sig2Root().getChild(44).getChild(asset.coinType())
                .getChild(key.biz()).getChild(Math.toIntExact(key.userId())).getChild(key.index());
    }
}
