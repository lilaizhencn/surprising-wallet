package com.surprising.wallet.service.chain.future;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TonChainAdapter implements BlockchainAdapter {
    @Override
    public ChainType chainType() {
        return ChainType.TON;
    }

    @Override
    public String family() {
        return "ton";
    }

    @Override
    public String describe() {
        return "Future-chain adapter for TON cell model and jetton transfer engine.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        return TransferQuote.unsupported(request.chainType(), request.assetSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), "ton runtime not connected yet");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        throw new UnsupportedOperationException("TON scanner is blocked until a TON cell/jetton RPC runtime is wired");
    }
}
