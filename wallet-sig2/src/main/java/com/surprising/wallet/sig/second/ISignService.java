package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoPartialSignature;

/** Native BTC, BCH, LTC and DOGE second-stage signing. */
public interface ISignService {
    String sign(UtxoSigningRequest request, UtxoPartialSignature partial);

    String chain();

    default boolean supports(AssetRuntimeMetadata asset) {
        return asset != null && chain().equalsIgnoreCase(asset.chain())
                && chain().equalsIgnoreCase(asset.assetSymbol());
    }
}
