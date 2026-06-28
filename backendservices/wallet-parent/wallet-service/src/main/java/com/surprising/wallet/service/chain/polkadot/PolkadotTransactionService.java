package com.surprising.wallet.service.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PolkadotTransactionService {
    private static final String CHAIN = PolkadotRuntimeClient.CHAIN;
    private static final String SYMBOL = "DOT";
    private static final int DOT_DECIMALS = 10;

    private final PolkadotRuntimeClient runtimeClient;
    private final PolkadotKeyService keyService;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountPlanck) {
        return sendNative(from, toAddress, amountPlanck, true);
    }

    private String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountPlanck,
                              boolean keepAlive) {
        PolkadotRuntimeClient.SubmittedTransaction tx = runtimeClient.sendNative(
                secretSeedHex(from), from.getAddress(), toAddress, amountPlanck, keepAlive);
        return tx.txHash();
    }

    public String sendAsset(ChainAddressRecord from, TokenDefinition token, String toAddress, BigDecimal amount) {
        String assetId = PolkadotRuntimeClient.normalizeAssetId(token.getContractAddress());
        if (assetId.isBlank()) {
            throw new IllegalStateException("missing DOT Asset Hub asset id for " + token.getSymbol());
        }
        PolkadotRuntimeClient.SubmittedTransaction tx = runtimeClient.sendAsset(
                secretSeedHex(from), from.getAddress(), assetId, toAddress,
                toAtomic(amount, token.getDecimals()));
        return tx.txHash();
    }

    public boolean confirmWithdrawal(AccountChainProfile profile, String orderNo, String txHash,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        if (!transactionFinalized(assetSymbol, txHash, confirmationLookback(profile))) {
            return false;
        }
        if (repository.markWithdrawalConfirmed(CHAIN, orderNo, txHash) == 1) {
            if (!repository.settleLockedDebit(CHAIN, assetSymbol, debitAccountId, debitAmount)) {
                throw new IllegalStateException("unable to settle DOT locked balance");
            }
            return true;
        }
        return false;
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger amountPlanck) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "polkadot collectNative");
        return collect(collectionNo, () -> sendNative(from, hotAddress, amountPlanck, false));
    }

    public String collectAsset(String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "polkadot collectAsset");
        return collect(collectionNo, () -> sendAsset(from, token, hotAddress, amount));
    }

    public boolean confirmCollection(AccountChainProfile profile, String collectionNo, String assetSymbol) {
        String txHash = repository.findCollectionTxHash(CHAIN, collectionNo).orElseThrow();
        if (transactionFinalized(assetSymbol, txHash, confirmationLookback(profile))) {
            return repository.markCollectionConfirmed(CHAIN, collectionNo, txHash) == 1;
        }
        return false;
    }

    public static BigInteger toPlanck(BigDecimal amount) {
        return toAtomic(amount, DOT_DECIMALS);
    }

    public static BigDecimal fromPlanck(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount).movePointLeft(DOT_DECIMALS)
                .stripTrailingZeros();
    }

    private String collect(String collectionNo, TxSubmitter submitter) {
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("DOT collection is not retryable"));
        }
        try {
            String txHash = submitter.submit();
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    private String secretSeedHex(ChainAddressRecord from) {
        Ed25519DerivedKey key = keyService.derive(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return HexFormat.of().formatHex(key.privateSeed());
    }

    private static int confirmationLookback(AccountChainProfile profile) {
        Integer configured = profile.getWithdrawConfirmations();
        int confirmations = configured == null || configured <= 0 ? 12 : configured;
        return Math.max(512, confirmations * 20);
    }

    private boolean transactionFinalized(String assetSymbol, String txHash, int maxRecentBlocks) {
        if (SYMBOL.equalsIgnoreCase(assetSymbol)) {
            return runtimeClient.transactionFinalized(txHash, maxRecentBlocks);
        }
        return runtimeClient.assetTransactionFinalized(txHash, maxRecentBlocks);
    }

    private static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    @FunctionalInterface
    private interface TxSubmitter {
        String submit();
    }
}
