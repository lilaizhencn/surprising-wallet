package com.surprising.wallet.model.custody;


/**
 * Gas 定价元数据。
 */
public record GasPricingMetadata(
        String family,
        String nativeSymbol,
        long defaultFeeRate,
        int decimals,
        boolean requestedNative
) {
}
