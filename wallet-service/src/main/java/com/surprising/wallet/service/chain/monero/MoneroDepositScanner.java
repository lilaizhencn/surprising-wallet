package com.surprising.wallet.service.chain.monero;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.MoneroTransactionRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public
class MoneroDepositScanner {
    private static final String CHAIN = MoneroWalletRpcClient.CHAIN;    private static final String SYMBOL = MoneroWalletRpcClient.SYMBOL;    private static final String SCANNER_NAME = "monero-wallet-rpc";    private final MoneroWalletRpcClient walletRpcClient;    private final ChainJdbcRepository repository;
    public void scanAndCredit(AccountChainProfile profile) {
        String network = profile == null ? null : profile.getNetwork();
        walletRpcClient.refresh(network, "rpc");
        long walletHeight = walletRpcClient.height(network, "rpc");
        int requiredConfirmations = profile.getDepositConfirmations() == null
                ? 1
                : Math.max(1, profile.getDepositConfirmations());
        long minHeight = repository.findScanSafeHeight(CHAIN, SCANNER_NAME)
                .map(height -> Math.max(0L, height - 20L))
                .orElseGet(() -> profile.getScanStartHeight() == null
                        ? 0L
                        : Math.max(0L, profile.getScanStartHeight() - 1L));
        Map<String, ChainAddressRecord> addresses = repository.listChainAddresses(CHAIN, SYMBOL).stream()
                .collect(Collectors.toMap(ChainAddressRecord::getAddress, address -> address, (left, right) -> left));
        Map<String, Integer> seenByTx = new HashMap<>();
        long bestHeight = minHeight;

        for (MoneroWalletRpcClient.Transfer transfer : walletRpcClient.incomingTransfers(minHeight, network, "rpc")) {
            bestHeight = Math.max(bestHeight, transfer.blockHeight());
            ChainAddressRecord address = addresses.get(transfer.toAddress());
            if (address == null) {
                continue;
            }
            int ordinal = seenByTx.merge(transfer.txHash(), 1, Integer::sum) - 1;
            long logIndex = (((long) transfer.subaddressIndex()) << 32) | (ordinal & 0xffffffffL);
            repository.recordMoneroTransaction(MoneroTransactionRecord.builder()
                    .chain(CHAIN)
                    .txHash(transfer.txHash())
                    .direction("IN")
                    .accountIndex(transfer.accountIndex())
                    .subaddressIndex(transfer.subaddressIndex())
                    .address(transfer.toAddress())
                    .assetSymbol(SYMBOL)
                    .amount(transfer.amount())
                    .feeAtomic(transfer.feeAtomic())
                    .blockHeight(transfer.blockHeight())
                    .confirmations(transfer.confirmations())
                    .status(transfer.confirmations() >= requiredConfirmations ? "CONFIRMED" : "CONFIRMING")
                    .rawPayload(transfer.rawPayload())
                    .build());
            repository.recordAndCreditDeposit(new DepositEvent(
                            ChainType.XMR,
                            SYMBOL,
                            transfer.txHash(),
                            transfer.fromAddress(),
                            transfer.toAddress(),
                            transfer.amount(),
                            transfer.blockHeight(),
                            transfer.txHash(),
                            transfer.confirmations(),
                            null,
                            transfer.rawPayload()),
                    logIndex,
                    requiredConfirmations,
                    address.getAccountId());
        }

        long bestObservedHeight = Math.max(bestHeight, walletHeight);
        if (bestObservedHeight > 0) {
            long safeHeight = Math.max(0L, Math.min(bestObservedHeight, walletHeight) - requiredConfirmations);
            repository.updateScanHeight(CHAIN, SCANNER_NAME, bestObservedHeight, safeHeight);
        }
    }
}
