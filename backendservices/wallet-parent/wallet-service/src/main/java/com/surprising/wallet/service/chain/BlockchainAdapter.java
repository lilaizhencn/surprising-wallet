package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

import java.util.List;
import java.util.Set;

public interface BlockchainAdapter {
    ChainType chainType();

    Set<Capability> capabilities();

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
        throw missing(Capability.DEPOSIT_SCAN);
    }

    default Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        throw missing(Capability.ADDRESS_GENERATION);
    }

    default Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        throw missing(Capability.ADDRESS_GENERATION);
    }

    default boolean checkAddress(ChainType chainType, String address) {
        throw missing(Capability.ADDRESS_VALIDATION);
    }

    default long depositConfirmationThreshold(ChainType chainType) {
        throw missing(Capability.CONFIRMATION_POLICY);
    }

    default long dustThresholdAtomic(ChainType chainType) {
        throw missing(Capability.DUST_POLICY);
    }

    default long bestHeight(ChainType chainType) {
        throw missing(Capability.BEST_HEIGHT);
    }

    default List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        throw missing(Capability.BLOCK_TRANSACTION_SCAN);
    }

    default void updateTransactionConfirmations(ChainType chainType) {
        throw missing(Capability.CONFIRMATION_REFRESH);
    }

    default void updateTotalBalance(ChainType chainType) {
        throw missing(Capability.BALANCE_REFRESH);
    }

    default String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        throw missing(Capability.SIGNED_TRANSACTION_BROADCAST);
    }

    private IllegalStateException missing(Capability capability) {
        return new IllegalStateException(
                chainType() + " adapter does not provide capability " + capability);
    }

    enum Capability {
        NATIVE_QUOTE,
        TOKEN_QUOTE,
        DEPOSIT_SCAN,
        ADDRESS_GENERATION,
        ADDRESS_VALIDATION,
        CONFIRMATION_POLICY,
        DUST_POLICY,
        BEST_HEIGHT,
        BLOCK_TRANSACTION_SCAN,
        CONFIRMATION_REFRESH,
        BALANCE_REFRESH,
        SIGNED_TRANSACTION_BROADCAST
    }
}
