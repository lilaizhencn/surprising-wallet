package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

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
        throw new UnsupportedOperationException("deposit scanning must be implemented by the concrete chain runtime");
    }

    default Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        throw new UnsupportedOperationException("deposit address generation is not implemented by this adapter");
    }

    default boolean checkAddress(ChainType chainType, String address) {
        throw new UnsupportedOperationException("address validation is not implemented by this adapter");
    }

    default long depositConfirmationThreshold(ChainType chainType) {
        throw new UnsupportedOperationException("deposit confirmation threshold is not implemented by this adapter");
    }

    default long dustThresholdAtomic(ChainType chainType) {
        throw new UnsupportedOperationException("dust threshold is not implemented by this adapter");
    }

    default long bestHeight(ChainType chainType) {
        throw new UnsupportedOperationException("best height lookup is not implemented by this adapter");
    }

    default List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        throw new UnsupportedOperationException("block transaction scanning is not implemented by this adapter");
    }

    default void updateTransactionConfirmations(ChainType chainType) {
        throw new UnsupportedOperationException("transaction confirmation refresh is not implemented by this adapter");
    }

    default void updateTotalBalance(ChainType chainType) {
        throw new UnsupportedOperationException("total balance refresh is not implemented by this adapter");
    }

    default String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        throw new UnsupportedOperationException("signed transaction broadcast is not implemented by this adapter");
    }
}
