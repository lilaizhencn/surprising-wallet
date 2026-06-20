package com.surprising.wallet.service.chain.tron;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class TronChainAdapter implements BlockchainAdapter {
    private final TronEnergyEstimator energyEstimator = new TronEnergyEstimator();

    @Override
    public ChainType chainType() {
        return ChainType.TRON;
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

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        throw new UnsupportedOperationException("TRON deposit scanning requires the RPC-backed TronScanner runtime; empty scans are forbidden");
    }
}
