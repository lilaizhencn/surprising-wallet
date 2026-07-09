package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

/**
 * @author atomex
 */

public interface ISignService {
    /**
     * 返回签名结果
     *
     * @param transaction
     */
    String signTransaction(WithdrawTransaction transaction);

    /**
     * 获取currency对应的签名类
     */
    String chain();

    default String assetSymbol() {
        return chain();
    }

    default boolean supports(AssetRuntimeMetadata asset) {
        if (asset == null || !chain().equalsIgnoreCase(asset.chain())) {
            return false;
        }
        String symbol = assetSymbol();
        return "*".equals(symbol) || symbol.equalsIgnoreCase(asset.assetSymbol());
    }
}
