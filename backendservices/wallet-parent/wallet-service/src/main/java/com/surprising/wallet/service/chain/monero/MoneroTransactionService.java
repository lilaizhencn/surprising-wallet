package com.surprising.wallet.service.chain.monero;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.MoneroTransactionRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class MoneroTransactionService {
    private static final String CHAIN = MoneroWalletRpcClient.CHAIN;
    private static final String SYMBOL = MoneroWalletRpcClient.SYMBOL;

    private final MoneroWalletRpcClient walletRpcClient;
    private final ChainJdbcRepository repository;

    public String sendNative(AccountChainProfile profile, ChainAddressRecord from, String toAddress, BigDecimal amount) {
        MoneroWalletRpcClient.Transfer transfer = walletRpcClient.transfer(
                Math.toIntExact(from.getAddressIndex()), toAddress, amount, network(profile), "rpc");
        repository.recordMoneroTransaction(MoneroTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(transfer.txHash())
                .direction("OUT")
                .accountIndex(transfer.accountIndex())
                .subaddressIndex(transfer.subaddressIndex())
                .address(from.getAddress())
                .assetSymbol(SYMBOL)
                .amount(amount)
                .feeAtomic(transfer.feeAtomic())
                .blockHeight(transfer.blockHeight())
                .confirmations(transfer.confirmations())
                .status("SENT")
                .rawPayload(transfer.rawPayload())
                .build());
        return transfer.txHash();
    }

    public void confirmWithdrawal(AccountChainProfile profile, String orderNo, String txHash,
                                  String debitAccountId, BigDecimal debitAmount,
                                  String toAddress, BigDecimal amount) {
        MoneroWalletRpcClient.Transfer transfer = confirmedTransfer(profile, txHash, profile.getWithdrawConfirmations());
        if (transfer != null) {
            if (repository.confirmWithdrawalAndSettle(CHAIN, orderNo, txHash, SYMBOL, debitAccountId, debitAmount)) {
                creditInternalRecipient(profile, transfer, toAddress, amount);
            }
        }
    }

    public void collectNative(AccountChainProfile profile, String collectionNo,
                              ChainAddressRecord from, String toAddress, BigDecimal amount) {
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return;
        }
        try {
            String txHash = sendNative(profile, from, toAddress, amount);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", txHash, null, null);
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public void confirmCollection(AccountChainProfile profile, String collectionNo) {
        repository.findCollectionTxHash(CHAIN, collectionNo)
                .filter(txHash -> confirmedTransfer(profile, txHash, profile.getWithdrawConfirmations()) != null)
                .ifPresent(txHash -> repository.markCollectionConfirmed(CHAIN, collectionNo, txHash));
    }

    private void creditInternalRecipient(AccountChainProfile profile, MoneroWalletRpcClient.Transfer transfer,
                                         String toAddress, BigDecimal amount) {
        if (toAddress == null || toAddress.isBlank()) {
            return;
        }
        repository.findChainAddressByAddress(CHAIN, SYMBOL, toAddress)
                .ifPresent(recipient -> repository.recordAndCreditDeposit(new DepositEvent(
                                ChainType.XMR,
                                SYMBOL,
                                transfer.txHash(),
                                transfer.fromAddress(),
                                toAddress,
                                amount,
                                transfer.blockHeight(),
                                transfer.confirmations(),
                                null,
                                transfer.rawPayload()),
                        scannerLogIndex(recipient),
                        Math.max(1, profile.getDepositConfirmations()),
                        recipient.getAccountId()));
    }

    private static long scannerLogIndex(ChainAddressRecord recipient) {
        long subaddressIndex = recipient.getAddressIndex() == null ? 0L : recipient.getAddressIndex();
        return subaddressIndex << 32;
    }

    private MoneroWalletRpcClient.Transfer confirmedTransfer(AccountChainProfile profile,
                                                            String txHash,
                                                            int requiredConfirmations) {
        if (txHash == null || txHash.isBlank()) {
            return null;
        }
        MoneroWalletRpcClient.Transfer transfer = walletRpcClient.transferByTxHash(txHash, network(profile), "rpc");
        if (transfer == null) {
            return null;
        }
        int confirmations = Math.max(0, transfer.confirmations());
        int required = Math.max(1, requiredConfirmations);
        repository.recordMoneroTransaction(MoneroTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(transfer.txHash())
                .direction(transfer.direction())
                .accountIndex(transfer.accountIndex())
                .subaddressIndex(transfer.subaddressIndex())
                .address(transfer.toAddress())
                .assetSymbol(SYMBOL)
                .amount(transfer.amount())
                .feeAtomic(transfer.feeAtomic())
                .blockHeight(transfer.blockHeight())
                .confirmations(confirmations)
                .status(confirmations >= required ? "CONFIRMED" : "SENT")
                .rawPayload(transfer.rawPayload())
                .build());
        return confirmations >= required ? transfer : null;
    }

    private static String network(AccountChainProfile profile) {
        return profile == null ? null : profile.getNetwork();
    }
}
