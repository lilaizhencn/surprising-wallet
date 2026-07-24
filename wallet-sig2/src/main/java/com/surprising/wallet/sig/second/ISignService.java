package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

/**
 * 签名服务统一接口，所有链的二签实现必须实现此接口。
 *
 * <p>每个实现类对应该链的一种资产类型（如 ETH 主币、ERC20 代币、BTC 等）。
 * 通过 {@link #supports(AssetRuntimeMetadata)} 声明自己能处理的链和资产，
 * 调用方无需硬编码链类型与实现类的映射关系。
 *
 * @author atomex
 */
public interface ISignService {

    /**
     * 对提现交易执行第二次签名，返回签名后的原始交易十六进制字符串。
     *
     * @param transaction 提现交易（包含签名元数据和资产信息）
     * @return 签名后的交易十六进制字符串，签名失败时返回空字符串
     */
    String signTransaction(WithdrawTransaction transaction);

    /**
     * 返回当前签名服务支持的链名称（如 ETH、BTC、TRON）。
     *
     * @return 链名称（大写）
     */
    String chain();

    /**
     * 返回当前签名服务支持的资产符号，默认与链名称相同。
     *
     * <p>覆盖此方法可支持同一链上的不同资产类型，如 ERC20 代币返回 "*"。
     *
     * @return 资产符号
     */

    default String assetSymbol() {
        return chain();
    }

    /**
     * 判断当前签名服务是否支持给定的资产。
     *
     * <p>默认实现：链名称匹配且（资产符号为 "*" 通配或精确匹配）。
     * 子类可以覆盖此方法实现更复杂的匹配逻辑（如 ERC20 排除主币）。
     *
     * @param asset 资产运行时元数据（包含 chain 和 assetSymbol）
     * @return true 表示支持该资产的签名
     */
    default boolean supports(AssetRuntimeMetadata asset) {
        if (asset == null || !chain().equalsIgnoreCase(asset.chain())) {
            return false;
        }
        String symbol = assetSymbol();
        return "*".equals(symbol) || symbol.equalsIgnoreCase(asset.assetSymbol());
    }
}
