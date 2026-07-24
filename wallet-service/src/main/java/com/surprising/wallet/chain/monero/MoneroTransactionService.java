package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.MoneroTransactionRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Monero 链交易服务，通过 monero-wallet-rpc 发送转账并管理提现/归集流程。
 *
 * <p>Monero 交易由 wallet-rpc 全权处理（包括签名和广播），本服务只需调用
 * transfer 方法即可完成转账。提现确认通过 get_transfer_by_txid 查询交易状态，
 * 达到所需确认数后视为已确认。</p>
 *
 * @see MoneroWalletRpcClient
 */
@Service
@RequiredArgsConstructor
public
class MoneroTransactionService {

    /** 链标识 */
    private static final String CHAIN = MoneroWalletRpcClient.CHAIN;

    /** 原生币符号 */
    private static final String SYMBOL = MoneroWalletRpcClient.SYMBOL;

    /** Monero 钱包 RPC 客户端 */
    private final MoneroWalletRpcClient walletRpcClient;

    /** 数据库仓库 */
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
        confirmWithdrawal(repository.requireWithdrawalTenant(CHAIN, orderNo),
                profile, orderNo, txHash, debitAccountId, debitAmount, toAddress, amount);
    }

    public void confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                  String orderNo, String txHash,
                                  String debitAccountId, BigDecimal debitAmount,
                                  String toAddress, BigDecimal amount) {
        MoneroWalletRpcClient.Transfer transfer = confirmedTransfer(profile, txHash, profile.getWithdrawConfirmations());
        if (transfer != null) {
            if (repository.confirmWithdrawalAndSettle(
                    tenantId, CHAIN, orderNo, txHash, SYMBOL, debitAccountId, debitAmount)) {
                creditInternalRecipient(profile, transfer, toAddress, amount);
            }
        }
    }

    public void collectNative(java.util.UUID tenantId, AccountChainProfile profile, String collectionNo,
                              ChainAddressRecord from, String toAddress, BigDecimal amount) {
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return;
        }
        try {
            String txHash = sendNative(profile, from, toAddress, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public void confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                  String collectionNo) {
        repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                .filter(txHash -> confirmedTransfer(profile, txHash, profile.getWithdrawConfirmations()) != null)
                .ifPresent(txHash -> repository.markCollectionConfirmed(
                        tenantId, CHAIN, collectionNo, txHash));
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
                                transfer.txHash(),
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
