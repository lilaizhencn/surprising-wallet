package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

/**
 * 首次签名服务统一接口，所有链的一签实现必须实现此接口。
 *
 * <p>每个实现类对应一条链的一种资产类型（如 BTC P2WSH、BCH P2SH、DOGE P2SH）。
 * sig1 持有第一组密钥分片，签名后将结果（firstSignTx）写入 transaction.signature，
 * 由调度任务推送到 Redis 二签队列。
 *
 * @author lilaizhen
 */
public interface ISignService {

    /**
     * 对提现交易执行第一次签名，结果直接写入 transaction.signature。
     *
     * <p>签名成功后会在 signature JSON 中设置 valid=true 及相关字段
     * （firstSignTx、witnessScripts、utxoValues 等），失败则设置 valid=false 和 error。
     *
     * @param transaction 提现交易
     */
    void signTransaction(WithdrawTransaction transaction);

    /**
     * 返回当前签名服务支持的链名称（如 BTC、BCH、DOGE）。
     *
     * @return 链名称（大写）
     */
    String chain();

    /**
     * 返回当前签名服务支持的资产符号，默认与链名称相同。
     *
     * @return 资产符号
     */
    default String assetSymbol() {
        return chain();
    }

    /**
     * 判断当前签名服务是否支持给定的资产。
     *
     * <p>默认实现：链名称匹配且资产符号匹配或为通配符 "*"。
     *
     * @param asset 资产运行时元数据
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
