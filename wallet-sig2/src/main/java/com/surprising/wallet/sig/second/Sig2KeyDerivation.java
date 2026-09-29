package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class Sig2KeyDerivation {
    private final WalletKeyMaterialProvider keys;
    public Sig2KeyDerivation(@Qualifier("sig2KeyMaterial") WalletKeyMaterialProvider keys) { this.keys = keys; }
    public Bip32Node derive(Address address, AssetRuntimeMetadata currency) {
        return keys.sig2Root().getChild(44).getChild(currency.getDerivationCoinType())
                .getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex());
    }
}
