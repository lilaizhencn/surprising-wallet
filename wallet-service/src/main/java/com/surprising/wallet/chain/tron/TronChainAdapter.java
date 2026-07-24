package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * TRON 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>提供 TRX 原生币和 TRC20 代币的转账报价。TRON 使用带宽（Bandwidth）和能量（Energy）
 * 资源模型，而非传统 Gas 费用。报价时通过 {@link TronEnergyEstimator} 估算所需资源。</p>
 *
 * <p>支持的链能力：{@link Capability#NATIVE_QUOTE} 和 {@link Capability#TOKEN_QUOTE}。</p>
 */
@Component
public
class TronChainAdapter implements BlockchainAdapter {

    /** 能量/带宽估算器 */
    private final TronEnergyEstimator energyEstimator = new TronEnergyEstimator();

    @Override
    public ChainType chainType() {
        return ChainType.TRON;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    @Override
    public String family() {
        return "tron";
    }

    @Override
    public String describe() {
        return "TRON adapter with TRX and TRC20 resource accounting.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long bandwidth = energyEstimator.estimateBandwidth(false);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), BigDecimal.ZERO, 0L, bandwidth, 0L, 0L, null, true,
                "tron native transfer");
    }

    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        long energy = energyEstimator.estimateBandwidth(true);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), BigDecimal.ZERO, 0L, energy, 0L, 0L, null, true,
                "tron trc20 transfer");
    }

}
