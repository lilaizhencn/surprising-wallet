package com.surprising.wallet.service.chain.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.SuiTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SuiTransactionService {
    private static final String CHAIN = "SUI";

    private final SuiRpcClient rpc;
    private final SuiTransactionSigner signer;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public String sendNative(long derivationIndex, String fromAddress, String toAddress, long amountMist) {
        return sendNative(0L, 0, derivationIndex, fromAddress, toAddress, amountMist);
    }

    public String sendNative(ChainAddressRecord from, String toAddress, long amountMist) {
        return sendNative(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), toAddress, amountMist);
    }

    private String sendNative(long userId, int biz, long derivationIndex,
                              String fromAddress, String toAddress, long amountMist) {
        long gasBudget = profile().getDefaultFee();
        List<String> inputCoins = selectCoins(fromAddress, SuiRpcClient.SUI_COIN_TYPE,
                BigDecimal.valueOf(amountMist + gasBudget));
        String txBytes = rpc.buildPaySui(fromAddress, inputCoins, toAddress, amountMist, gasBudget);
        String signature = signer.signTransactionBytes(userId, biz, derivationIndex, txBytes);
        return rpc.executeTransactionBlock(txBytes, signature).path("digest").asText();
    }

    public String sendCoin(long derivationIndex, String fromAddress, String coinType,
                           String toAddress, long amountAtomic) {
        return sendCoin(0L, 0, derivationIndex, fromAddress, coinType, toAddress, amountAtomic);
    }

    public String sendCoin(ChainAddressRecord from, String coinType, String toAddress, long amountAtomic) {
        return sendCoin(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), coinType, toAddress, amountAtomic);
    }

    private String sendCoin(long userId, int biz, long derivationIndex, String fromAddress, String coinType,
                            String toAddress, long amountAtomic) {
        long gasBudget = profile().getDefaultFee();
        List<String> inputCoins = selectCoins(fromAddress, coinType, BigDecimal.valueOf(amountAtomic));
        String gasObject = selectCoins(fromAddress, SuiRpcClient.SUI_COIN_TYPE,
                BigDecimal.valueOf(gasBudget)).get(0);
        String txBytes = rpc.buildPayCoin(fromAddress, inputCoins, toAddress, amountAtomic, gasObject, gasBudget);
        String signature = signer.signTransactionBytes(userId, biz, derivationIndex, txBytes);
        return rpc.executeTransactionBlock(txBytes, signature).path("digest").asText();
    }

    public String withdrawNative(String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amountMist) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "sui withdrawNative");
        Optional<String> existing = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeReserve = profile().getDefaultFee();
        if (repository.createWithdrawalOrder(orderNo, userId, CHAIN, "SUI", toAddress,
                amountMist, BigDecimal.valueOf(feeReserve)) == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Sui withdrawal already claimed"));
        }
        BigDecimal debit = amountMist.add(BigDecimal.valueOf(feeReserve));
        if (!repository.freezeLedgerBalance(CHAIN, "SUI", from.getAccountId(), debit)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient SUI ledger balance");
            throw new IllegalStateException("insufficient SUI ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SIGNING", from.getAddress(), null, null);
            String digest = sendNative(from, toAddress, amountMist.longValueExact());
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SENT", from.getAddress(), digest, null);
            record(digest, from.getAddress(), toAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE, amountMist,
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.releaseLockedBalance(CHAIN, "SUI", from.getAccountId(), debit);
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null, e.getMessage());
            throw e;
        }
    }

    public String withdrawCoin(String orderNo, long userId, ChainAddressRecord from,
                               String coinType, String toAddress, BigDecimal amountAtomic) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "sui withdrawCoin");
        Optional<String> existing = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, coinType)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Sui coin " + coinType));
        if (repository.createWithdrawalOrder(orderNo, userId, CHAIN, token.getSymbol(), toAddress,
                amountAtomic, BigDecimal.ZERO) == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Sui coin withdrawal already claimed"));
        }
        if (!repository.freezeLedgerBalance(CHAIN, token.getSymbol(), from.getAccountId(), amountAtomic)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient " + token.getSymbol() + " ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SIGNING", from.getAddress(), null, null);
            String digest = sendCoin(from, coinType, toAddress, amountAtomic.longValueExact());
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SENT", from.getAddress(), digest, null);
            record(digest, from.getAddress(), toAddress, token.getSymbol(), coinType, amountAtomic,
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.releaseLockedBalance(CHAIN, token.getSymbol(), from.getAccountId(), amountAtomic);
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null, e.getMessage());
            throw e;
        }
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountMist) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "sui collectNative");
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeReserve = profile().getDefaultFee();
        repository.createCollectionRecord(collectionNo, CHAIN, "SUI", from.getAddress(), hotAddress,
                amountMist, BigDecimal.valueOf(feeReserve), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Sui collection is not retryable"));
        }
        try {
            String digest = sendNative(from, hotAddress, amountMist.longValueExact());
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", digest, null, null);
            record(digest, from.getAddress(), hotAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE, amountMist,
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectCoin(String collectionNo, ChainAddressRecord from, String coinType,
                              String hotAddress, BigDecimal amountAtomic) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "sui collectCoin");
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, coinType)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Sui coin " + coinType));
        repository.createCollectionRecord(collectionNo, CHAIN, token.getSymbol(), from.getAddress(),
                hotAddress, amountAtomic, BigDecimal.valueOf(profile().getDefaultFee()), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Sui coin collection is not retryable"));
        }
        try {
            String digest = sendCoin(from, coinType, hotAddress, amountAtomic.longValueExact());
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", digest, null, null);
            record(digest, from.getAddress(), hotAddress, token.getSymbol(), coinType, amountAtomic,
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmWithdrawal(String orderNo, String assetSymbol, String accountId, BigDecimal debitAmount) {
        String digest = repository.findWithdrawalTxHash(CHAIN, orderNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(digest, Duration.ofMinutes(2));
        if (repository.markWithdrawalConfirmed(CHAIN, orderNo, digest) == 1) {
            if (!repository.settleLockedDebit(CHAIN, assetSymbol, accountId, debitAmount)) {
                throw new IllegalStateException("unable to settle Sui locked balance");
            }
            markConfirmed(digest, transaction);
            return true;
        }
        return false;
    }

    public boolean confirmCollection(String collectionNo) {
        String digest = repository.findCollectionTxHash(CHAIN, collectionNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(digest, Duration.ofMinutes(2));
        if (repository.markCollectionConfirmed(CHAIN, collectionNo, digest) == 1) {
            markConfirmed(digest, transaction);
            return true;
        }
        return false;
    }

    public JsonNode requireSuccessfulConfirmation(String digest, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode transaction = rpc.transactionBlock(digest);
            String status = transaction.path("effects").path("status").path("status").asText("");
            if ("success".equals(status)) {
                return transaction;
            }
            if ("failure".equals(status)) {
                throw new IllegalStateException("Sui transaction failed: "
                        + transaction.path("effects").path("status").path("error").asText());
            }
            sleep(750L);
        }
        throw new IllegalStateException("Sui confirmation timeout for " + digest);
    }

    private List<String> selectCoins(String owner, String coinType, BigDecimal required) {
        List<String> selected = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SuiRpcClient.SuiCoin coin : rpc.coins(owner, coinType, 50)) {
            selected.add(coin.objectId());
            total = total.add(coin.balance());
            if (total.compareTo(required) >= 0) {
                return selected;
            }
        }
        throw new IllegalStateException("insufficient on-chain " + coinType + " balance");
    }

    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    private void record(String digest, String sender, String receiver, String symbol, String coinType,
                        BigDecimal amount, long feeReserve, String status, String rawPayload) {
        repository.recordSuiTransaction(SuiTransactionRecord.builder()
                .chain(CHAIN)
                .txDigest(digest)
                .sender(SuiHex.normalizeAddress(sender))
                .receiver(SuiHex.normalizeAddress(receiver))
                .assetSymbol(symbol)
                .coinType(coinType)
                .amount(amount)
                .gasUsed(feeReserve)
                .checkpoint(null)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }

    private void markConfirmed(String digest, JsonNode transaction) {
        long gasUsed = totalGas(transaction.path("effects").path("gasUsed"));
        long checkpoint = transaction.path("checkpoint").asLong(0L);
        repository.markSuiTransactionConfirmed(CHAIN, digest, checkpoint, gasUsed, transaction.toString());
    }

    private long totalGas(JsonNode gas) {
        return gas.path("computationCost").asLong(0)
                + gas.path("storageCost").asLong(0)
                - gas.path("storageRebate").asLong(0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui wait interrupted", e);
        }
    }
}
