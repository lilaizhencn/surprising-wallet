package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.ChainProfile;
import com.surprising.wallet.common.chain.TransferRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fee estimator for EVM-compatible chains.
 */
@Component
public class EvmGasEstimator {
    private static final BigDecimal WEI_PER_GWEI = BigDecimal.valueOf(1_000_000_000L);

    public GasQuote estimateNative(ChainProfile profile, BigDecimal gasPriceGwei) {
        return estimate(profile, gasPriceGwei, BigDecimal.valueOf(21_000L));
    }

    public GasQuote estimateErc20(ChainProfile profile, BigDecimal gasPriceGwei) {
        return estimate(profile, gasPriceGwei, BigDecimal.valueOf(65_000L));
    }

    public GasQuote estimate(ChainProfile profile, BigDecimal gasPriceGwei, BigDecimal gasLimit) {
        if (profile == null || gasPriceGwei == null || gasLimit == null) {
            throw new IllegalArgumentException("profile, gasPriceGwei and gasLimit are required");
        }
        BigDecimal feeWei = gasPriceGwei.multiply(WEI_PER_GWEI).multiply(gasLimit);
        return new GasQuote(profile.getChainId(), gasLimit.longValue(), gasPriceGwei.longValue(),
                feeWei.toBigIntegerExact().longValue());
    }

    public GasQuote quote(ChainProfile profile, TransferRequest request, boolean tokenTransfer) {
        BigDecimal gasPrice = profile.getGasPriceFloor() == null ? BigDecimal.ZERO : profile.getGasPriceFloor();
        return tokenTransfer ? estimateErc20(profile, gasPrice) : estimateNative(profile, gasPrice);
    }

    public record GasQuote(Long chainId, long gasLimit, long gasPriceGwei, long feeWei) {
        public BigDecimal feeEth() {
            return BigDecimal.valueOf(feeWei).divide(BigDecimal.valueOf(1_000_000_000_000_000_000L), 18, RoundingMode.DOWN);
        }
    }
}
