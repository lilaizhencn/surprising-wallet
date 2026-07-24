package com.surprising.wallet.service.chain.cardano;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public
class CardanoTransactionService {
    private static final String CHAIN = CardanoBackendClient.CHAIN;
    private static final String SYMBOL = "ADA";
    private static final int ADA_DECIMALS = 6;
    private final CardanoBackendClient backendClient;
    private final CardanoKeyService keyService;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    public String sendNative(ChainAddressRecord from, String toAddress, BigInteger lovelace) {
        return send(from, toAddress, Amount.lovelace(lovelace));
    }
    public String sendToken(ChainAddressRecord from, TokenDefinition token, String toAddress, BigDecimal amount) {
        BigInteger atomic = toAtomic(amount, token.getDecimals());
        String unit = CardanoAssetUnit.fromTokenContract(token.getContractAddress());
        return send(from, toAddress, Amount.asset(unit, atomic));
    }

    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String txHash,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        if (!confirmed(profile, txHash)) {
            return false;
        }
        return repository.confirmWithdrawalAndSettle(tenantId, CHAIN, orderNo, txHash,
                assetSymbol, debitAccountId, debitAmount);
    }

    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger lovelace) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "cardano collectNative");
        return collect(tenantId, collectionNo, () -> sendNative(from, hotAddress, lovelace));
    }

    public String collectToken(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "cardano collectToken");
        return collect(tenantId, collectionNo, () -> sendToken(from, token, hotAddress, amount));
    }

    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        if (confirmed(profile, txHash)) {
            return repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
        }
        return false;
    }
    public static BigInteger toLovelace(BigDecimal amount) {
        return toAtomic(amount, ADA_DECIMALS);
    }
    public static BigDecimal fromLovelace(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount).movePointLeft(ADA_DECIMALS)
                .stripTrailingZeros();
    }
    private String send(ChainAddressRecord from, String toAddress, Amount amount) {
        return backendClient.withBackend((backend, node, profile) -> {
            SecretKey secretKey = secretKey(from);
            Tx tx = new Tx()
                    .from(from.getAddress())
                    .payToAddress(toAddress, amount)
                    .withChangeAddress(from.getAddress());
            TxResult result = new QuickTxBuilder(backend)
                    .compose(tx)
                    .feePayer(from.getAddress())
                    .withSigner(SignerProviders.signerFrom(secretKey))
                    .complete();
            if (result == null || !result.isSuccessful()) {
                throw new IllegalStateException("Cardano transaction submit failed: "
                        + (result == null ? "<empty>" : result.getResponse()));
            }
            String hash = result.getTxHash();
            if (hash == null || hash.isBlank()) {
                hash = result.getValue();
            }
            if (hash == null || hash.isBlank()) {
                throw new IllegalStateException("Cardano transaction submit returned empty tx hash");
            }
            return hash;
        });
    }
    private String collect(java.util.UUID tenantId, String collectionNo, TxSubmitter submitter) {
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Cardano collection is not retryable"));
        }
        try {
            String txHash = submitter.submit();
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    private boolean confirmed(AccountChainProfile profile, String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return false;
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            boolean confirmed = backendClient.withBackend((backend, node, activeProfile) -> {
                var txResult = backend.getTransactionService().getTransaction(txHash);
                if (CardanoBackendClient.isNotFound(txResult)) {
                    return false;
                }
                var tx = CardanoBackendClient.requireSuccess(txResult, "transaction content");
                var latest = CardanoBackendClient.requireSuccess(
                        backend.getBlockService().getLatestBlock(), "latest block");
                long confirmations = Math.max(0L, latest.getHeight() - tx.getBlockHeight() + 1L);
                return confirmations >= Math.max(1, profile.getWithdrawConfirmations());
            });
            if (confirmed) {
                return true;
            }
            sleep(1_000L);
        }
        return false;
    }
    private Ed25519DerivedKey derivedKey(ChainAddressRecord from) {
        return keyService.derive(from.getUserId(), from.getBiz(), from.getAddressIndex());
    }
    private SecretKey secretKey(ChainAddressRecord from) {
        try {
            return SecretKey.create(derivedKey(from).privateSeed());
        } catch (CborSerializationException e) {
            throw new IllegalStateException("unable to create Cardano signing key", e);
        }
    }
    private static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cardano wait interrupted", e);
        }
    }

    @FunctionalInterface
    private interface TxSubmitter {
        String submit();
    }
}
