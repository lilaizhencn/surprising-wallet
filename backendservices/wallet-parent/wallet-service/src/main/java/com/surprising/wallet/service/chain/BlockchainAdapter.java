package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;

import java.util.List;

public interface BlockchainAdapter {
    ChainType chainType();

    default boolean supports(ChainType chainType) {
        return chainType() == chainType;
    }

    String family();

    String describe();

    TransferQuote quoteNativeTransfer(TransferRequest request);

    default TransferQuote quoteTokenTransfer(TransferRequest request) {
        return TransferQuote.unsupported(request.chainType(), request.assetSymbol(),
                request.fromAddress(), request.toAddress(), request.amount(),
                "token transfer not supported by this adapter");
    }

    default List<DepositEvent> scanDeposits(long height) {
        return List.of();
    }
}
