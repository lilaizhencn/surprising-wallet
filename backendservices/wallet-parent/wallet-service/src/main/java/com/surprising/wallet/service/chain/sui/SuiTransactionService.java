package com.surprising.wallet.service.chain.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.SuiTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SuiTransactionService {
    private static final String CHAIN = "SUI";

    private final SuiRpcClient rpc;
    private final SuiTransactionSigner signer;
    private final SuiPtbTransactionBuilder ptbBuilder;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    @Autowired
    public SuiTransactionService(SuiRpcClient rpc, SuiTransactionSigner signer,
                                 SuiPtbTransactionBuilder ptbBuilder, ChainJdbcRepository repository) {
        this.rpc = rpc;
        this.signer = signer;
        this.ptbBuilder = ptbBuilder;
        this.repository = repository;
    }

    SuiTransactionService(SuiRpcClient rpc, SuiTransactionSigner signer, ChainJdbcRepository repository) {
        this(rpc, signer, new SuiPtbTransactionBuilder(), repository);
    }

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
        long gasPrice = rpc.referenceGasPrice();
        List<SuiRpcClient.SuiCoin> gasPayment = selectCoins(fromAddress, SuiRpcClient.SUI_COIN_TYPE,
                BigDecimal.valueOf(amountMist + gasBudget));
        String txBytes = ptbBuilder.buildSuiTransfer(fromAddress, gasPayment, toAddress,
                amountMist, gasPrice, gasBudget);
        String signature = signer.signTransactionBytes(userId, biz, derivationIndex, txBytes);
        return rpc.executeSignedTransaction(txBytes, signature).path("digest").asText();
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
        long gasPrice = rpc.referenceGasPrice();
        List<SuiRpcClient.SuiCoin> inputCoins = selectCoins(fromAddress, coinType, BigDecimal.valueOf(amountAtomic));
        List<SuiRpcClient.SuiCoin> gasPayment = selectCoins(fromAddress, SuiRpcClient.SUI_COIN_TYPE,
                BigDecimal.valueOf(gasBudget));
        String txBytes = ptbBuilder.buildCoinTransfer(fromAddress, inputCoins, gasPayment,
                toAddress, amountAtomic, gasPrice, gasBudget);
        String signature = signer.signTransactionBytes(userId, biz, derivationIndex, txBytes);
        return rpc.executeSignedTransaction(txBytes, signature).path("digest").asText();
    }

    public PublishResult publishPackage(ChainAddressRecord from, List<String> compiledModules,
                                        List<String> dependencies, long gasBudget) {
        if (gasBudget <= 0) {
            throw new IllegalArgumentException("Sui publish gas budget must be positive");
        }
        JsonNode unsigned = rpc.publishTransaction(from.getAddress(), compiledModules, dependencies, gasBudget);
        String txBytes = unsigned.path("txBytes").asText("");
        if (txBytes.isBlank()) {
            throw new IllegalStateException("Sui publish builder returned empty txBytes");
        }
        String signature = signer.signTransactionBytes(from.getUserId(), from.getBiz(), from.getAddressIndex(), txBytes);
        JsonNode result = rpc.executeSignedTransaction(txBytes, signature);
        String digest = result.path("digest").asText("");
        if (digest.isBlank()) {
            throw new IllegalStateException("Sui publish execution returned empty digest");
        }
        record(digest, from.getAddress(), from.getAddress(), "SUI_CONTRACT", "PACKAGE",
                BigDecimal.ZERO, gasBudget, "SENT", result.toString());
        return new PublishResult(digest, result);
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
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Sui withdrawal is not signable: " + orderNo);
            }
            String digest = sendNative(from, toAddress, amountMist.longValueExact());
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), digest) != 1) {
                throw new IllegalStateException("Sui withdrawal state changed before SENT: " + orderNo);
            }
            record(digest, from.getAddress(), toAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE, amountMist,
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getAddress(), e.getMessage());
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
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Sui coin withdrawal is not signable: " + orderNo);
            }
            String digest = sendCoin(from, coinType, toAddress, amountAtomic.longValueExact());
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), digest) != 1) {
                throw new IllegalStateException("Sui coin withdrawal state changed before SENT: " + orderNo);
            }
            record(digest, from.getAddress(), toAddress, token.getSymbol(), coinType, amountAtomic,
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getAddress(), e.getMessage());
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
        if (repository.confirmWithdrawalAndSettle(CHAIN, orderNo, digest, assetSymbol, accountId, debitAmount)) {
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

    private List<SuiRpcClient.SuiCoin> selectCoins(String owner, String coinType, BigDecimal required) {
        List<SuiRpcClient.SuiCoin> selected = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SuiRpcClient.SuiCoin coin : rpc.coins(owner, coinType, 50)) {
            selected.add(coin);
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

    public record PublishResult(String digest, JsonNode result) {
    }
}
