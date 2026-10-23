package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoPartialSignature;

/** Native BTC, BCH, LTC and DOGE first-stage signing. */
public interface ISignService {
    UtxoPartialSignature sign(UtxoSigningRequest request);

    String chain();

    default boolean supports(AssetRuntimeMetadata asset) {
        return asset != null && chain().equalsIgnoreCase(asset.chain())
                && chain().equalsIgnoreCase(asset.assetSymbol());
    }
}
