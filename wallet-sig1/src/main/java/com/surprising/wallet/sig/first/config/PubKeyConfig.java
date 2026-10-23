package com.surprising.wallet.sig.first.config;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Derives the public 2-of-3 scripts from the same coordinates used by both signers. */
@Component
public class PubKeyConfig {
    private final WalletKeyMaterialProvider keys;

    public PubKeyConfig(@Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys) {
        this.keys = keys;
    }

    public String genWitnessScript(UtxoSigningRequest.Key key, UtxoSigningRequest.Asset asset) {
        var generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(derive(keys.sig1PublicRoot(), key, asset));
        generator.addECKey(derive(keys.sig2PublicRoot(), key, asset));
        generator.addECKey(derive(keys.recoveryPublicRoot(), key, asset));
        generator.generateAddress(asset.network().parameters(asset.chain()), 2);
        return generator.getWitnessScriptStr();
    }

    public String genRedeemScript(UtxoSigningRequest.Key key, UtxoSigningRequest.Asset asset) {
        var generator = new LegacyMultiSignAddressGenerator();
        generator.addECKey(derive(keys.sig1PublicRoot(), key, asset));
        generator.addECKey(derive(keys.sig2PublicRoot(), key, asset));
        generator.addECKey(derive(keys.recoveryPublicRoot(), key, asset));
        generator.generateAddress(asset.network().parameters(asset.chain()), 2);
        String script = generator.getRedeemScriptHex();
        if (key.redeemScript() != null && !key.redeemScript().isBlank()
                && !key.redeemScript().equalsIgnoreCase(script)) {
            throw new IllegalStateException("stored redeemScript does not match derived multisig keys");
        }
        return script;
    }

    private static ECKey derive(Bip32Node root, UtxoSigningRequest.Key key, UtxoSigningRequest.Asset asset) {
        return root.getChild(44).getChild(asset.coinType()).getChild(key.biz())
                .getChild(Math.toIntExact(key.userId())).getChild(key.index()).getEcKey();
    }
}
