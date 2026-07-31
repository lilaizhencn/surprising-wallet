package com.surprising.wallet.chain.evm;

import com.surprising.wallet.chain.model.ChainProfile;
import com.surprising.wallet.chain.model.TransferRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 负责 EVM 链交易、费用、扫描或 EIP-7702 相关处理。
 */
@Component
public class EvmGasEstimator {
    /**
     * 定义 {@code WEI_PER_GWEI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal WEI_PER_GWEI = BigDecimal.valueOf(1_000_000_000L);
    /**
     * 计算或估算 {@code estimateNative} 对应的金额、费用或资源消耗。
     */
    public GasQuote estimateNative(ChainProfile profile, BigDecimal gasPriceGwei) {
        return estimate(profile, gasPriceGwei, BigDecimal.valueOf(21_000L));
    }
    /**
     * 计算或估算 {@code estimateErc20} 对应的金额、费用或资源消耗。
     */
    public GasQuote estimateErc20(ChainProfile profile, BigDecimal gasPriceGwei) {
        return estimate(profile, gasPriceGwei, BigDecimal.valueOf(65_000L));
    }
    /**
     * 计算或估算 {@code estimate} 对应的金额、费用或资源消耗。
     */
    public GasQuote estimate(ChainProfile profile, BigDecimal gasPriceGwei, BigDecimal gasLimit) {
        if (profile == null || gasPriceGwei == null || gasLimit == null) {
            throw new IllegalArgumentException("profile, gasPriceGwei and gasLimit are required");
        }
        BigDecimal feeWei = gasPriceGwei.multiply(WEI_PER_GWEI).multiply(gasLimit);
        return new GasQuote(profile.getChainId(), gasLimit.longValue(), gasPriceGwei.longValue(),
                feeWei.toBigIntegerExact().longValue());
    }
    /**
     * 计算或估算 {@code quote} 对应的金额、费用或资源消耗。
     */
    public GasQuote quote(ChainProfile profile, TransferRequest request, boolean tokenTransfer) {
        BigDecimal gasPrice = profile.getGasPriceFloor() == null ? BigDecimal.ZERO : profile.getGasPriceFloor();
        return tokenTransfer ? estimateErc20(profile, gasPrice) : estimateNative(profile, gasPrice);
    }
    public record GasQuote(Long chainId, long gasLimit, long gasPriceGwei, long feeWei) {
        /**
         * 转换或计算 {@code feeEth} 对应的值，统一金额、格式和边界规则。
         */
        public BigDecimal feeEth() {
            return BigDecimal.valueOf(feeWei).divide(BigDecimal.valueOf(1_000_000_000_000_000_000L), 18, RoundingMode.DOWN);
        }
    }
}
