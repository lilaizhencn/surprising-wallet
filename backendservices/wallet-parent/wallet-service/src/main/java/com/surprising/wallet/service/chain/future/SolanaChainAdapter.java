package com.surprising.wallet.service.chain.future;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class SolanaChainAdapter implements BlockchainAdapter {
    @Override
    public ChainType chainType() {
        return ChainType.SOLANA;
    }

    @Override
    public String family() {
        return "solana";
    }

    @Override
    public String describe() {
        return "Future-chain adapter for Solana account model, blockhash-based transfer flow and SPL token registry.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        return TransferQuote.unsupported(request.chainType(), request.assetSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), "solana runtime not connected yet");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return List.of();
    }
}
