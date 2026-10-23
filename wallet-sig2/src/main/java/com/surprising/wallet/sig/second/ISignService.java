package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

/** Native BTC, BCH, LTC and DOGE second-stage signing. */
public interface ISignService {
    String signTransaction(WithdrawTransaction transaction);

    String chain();

    default boolean supports(AssetRuntimeMetadata asset) {
        return asset != null && chain().equalsIgnoreCase(asset.chain())
                && chain().equalsIgnoreCase(asset.assetSymbol());
    }
}
