package com.surprising.wallet.service;

import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 观察在途交易，不受新扫描/提现开关影响，也不创建或重新广播交易。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BitcoinLikeConfirmationService {
    private final ChainProfileRepository profiles;
    private final BitcoinLikeDepositScanService deposits;
    private final BitcoinLikeChainRuntime runtime;
    private final ChainSigningTransactionRepository signing;
    private final BitcoinLikeSettlementService settlements;

    public void confirmAll() {
        for (var profile : profiles.listProfiles(true)) {
            if (!"bitcoin-like".equalsIgnoreCase(profile.getFamily())) continue;
            try { deposits.refresh(profile); }
            catch (RuntimeException error) { log.warn("UTXO deposit confirmation failed: chain={}", profile.getChain(), error); }
            confirmWithdrawals(profile);
        }
    }

    private void confirmWithdrawals(AccountChainProfile profile) {
        var type = ChainType.valueOf(profile.getChain());
        for (var transaction : signing.listPendingConfirmation(profile.getChain())) {
            try {
                for (String txId : transaction.transactionIds()) {
                    int confirmations = runtime.confirmations(type, txId);
                    if (confirmations >= profile.getWithdrawConfirmations()) {
                        settlements.settleConfirmed(profile.getChain(), transaction.id(), txId);
                        break;
                    }
                    if (confirmations > 0) {
                        settlements.markConfirming(profile.getChain(), transaction.id(), txId);
                        break;
                    }
                }
            } catch (RuntimeException error) {
                log.warn("UTXO withdrawal confirmation failed: chain={} id={}", profile.getChain(), transaction.id(), error);
            }
        }
    }
}
