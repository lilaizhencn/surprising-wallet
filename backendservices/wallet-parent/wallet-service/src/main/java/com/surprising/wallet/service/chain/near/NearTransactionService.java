package com.surprising.wallet.service.chain.near;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.NearTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class NearTransactionService {
    private static final String CHAIN = "NEAR";
    private static final String SYMBOL = "NEAR";
    private static final int DECIMALS = 24;
    private static final long DEFAULT_FUNCTION_CALL_GAS = 30_000_000_000_000L;
    private static final BigInteger ONE_YOCTO = BigInteger.ONE;

    private final NearRpcClient rpc;
    private final NearTransactionSigner signer;
    private final NearKeyService keyService;
    private final ChainJdbcRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public NearTransactionService(NearRpcClient rpc,
                                  NearTransactionSigner signer,
                                  NearKeyService keyService,
                                  ChainJdbcRepository repository) {
        this.rpc = rpc;
        this.signer = signer;
        this.keyService = keyService;
        this.repository = repository;
    }

    public String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountYocto) {
        JsonNode accessKey = rpc.accessKey(from.getAddress(), publicKeyBase58(from));
        long nonce = accessKey.path("nonce").asLong(0L) + 1L;
        NearTransactionSigner.SignedTransaction signed = signer.transfer(
                from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), nonce, toAddress,
                accessKey.path("block_hash").asText(), amountYocto);
        JsonNode result = rpc.broadcastTxCommit(signed.signedTransactionBase64());
        String txHash = result.path("transaction").path("hash").asText(signed.transactionHash());
        record(txHash, 0L, from.getAddress(), toAddress, SYMBOL, fromYocto(amountYocto),
                gasBurnt(result), blockHeight(result), txSucceeded(result) ? "CONFIRMED" : "SENT",
                result.toString());
        return txHash;
    }

    public boolean accountExists(String accountId) {
        return rpc.accountExists(accountId);
    }

    public String activateImplicitAccount(ChainAddressRecord payer, String accountId, BigInteger amountYocto) {
        if (!NearKeyService.isValidAccountId(accountId)) {
            throw new IllegalArgumentException("invalid NEAR account id: " + accountId);
        }
        if (amountYocto == null || amountYocto.signum() <= 0) {
            throw new IllegalArgumentException("NEAR activation amount must be positive");
        }
        return sendNative(payer, accountId, amountYocto);
    }

    public String sendToken(ChainAddressRecord from, TokenDefinition token, String toAccountId, BigDecimal amount) {
        BigInteger atomicAmount = toAtomic(amount, token.getDecimals());
        ObjectNode args = objectMapper.createObjectNode();
        args.put("receiver_id", toAccountId);
        args.put("amount", atomicAmount.toString());
        JsonNode result = sendFunctionCall(from, token.getContractAddress(), "ft_transfer",
                jsonBytes(args), DEFAULT_FUNCTION_CALL_GAS, ONE_YOCTO);
        String txHash = result.path("transaction").path("hash").asText();
        record(txHash, 0L, from.getAddress(), toAccountId, token.getSymbol(), amount,
                gasBurnt(result), blockHeight(result), txSucceeded(result) ? "CONFIRMED" : "SENT",
                result.toString());
        return txHash;
    }

    public String storageDeposit(ChainAddressRecord payer, TokenDefinition token,
                                 String accountId, BigInteger depositYocto) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("account_id", accountId);
        args.put("registration_only", true);
        JsonNode result = sendFunctionCall(payer, token.getContractAddress(), "storage_deposit",
                jsonBytes(args), DEFAULT_FUNCTION_CALL_GAS, depositYocto);
        String txHash = result.path("transaction").path("hash").asText();
        record(txHash, 0L, payer.getAddress(), accountId, token.getSymbol(), BigDecimal.ZERO,
                gasBurnt(result), blockHeight(result), txSucceeded(result) ? "CONFIRMED" : "SENT",
                result.toString());
        return txHash;
    }

    public boolean tokenStorageRegistered(TokenDefinition token, String accountId) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("account_id", accountId);
        JsonNode balance = rpc.viewFunctionJson(token.getContractAddress(), "storage_balance_of", jsonBytes(args));
        return balance != null && !balance.isNull() && !balance.isMissingNode();
    }

    public BigInteger tokenStorageMinimum(TokenDefinition token) {
        JsonNode bounds = rpc.viewFunctionJson(token.getContractAddress(), "storage_balance_bounds",
                "{}".getBytes(StandardCharsets.UTF_8));
        String min = bounds.path("min").asText("0");
        return new BigInteger(min);
    }

    public boolean confirmWithdrawal(AccountChainProfile profile, String orderNo, String txHash,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        JsonNode status = requireSuccessfulConfirmation(txHash, debitAccountId, Duration.ofMinutes(2));
        if (repository.markWithdrawalConfirmed(CHAIN, orderNo, txHash) == 1) {
            if (!repository.settleLockedDebit(CHAIN, assetSymbol, debitAccountId, debitAmount)) {
                throw new IllegalStateException("unable to settle NEAR locked balance");
            }
            repository.markNearTransactionConfirmed(CHAIN, txHash, blockHeight(status), gasBurnt(status),
                    status.toString());
            return true;
        }
        return false;
    }

    public boolean confirmWithdrawal(AccountChainProfile profile, String orderNo, String txHash,
                                     String debitAccountId, BigDecimal debitAmount) {
        return confirmWithdrawal(profile, orderNo, txHash, SYMBOL, debitAccountId, debitAmount);
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger amountYocto) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "near collectNative");
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("NEAR collection is not retryable"));
        }
        try {
            String txHash = sendNative(from, hotAddress, amountYocto);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectToken(String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "near collectToken");
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("NEAR token collection is not retryable"));
        }
        try {
            String txHash = sendToken(from, token, hotAddress, amount);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmCollection(AccountChainProfile profile, String collectionNo) {
        String txHash = repository.findCollectionTxHash(CHAIN, collectionNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(txHash, null, Duration.ofMinutes(2));
        if (repository.markCollectionConfirmed(CHAIN, collectionNo, txHash) == 1) {
            repository.markNearTransactionConfirmed(CHAIN, txHash, blockHeight(status), gasBurnt(status),
                    status.toString());
            return true;
        }
        return false;
    }

    public JsonNode requireSuccessfulConfirmation(String txHash, String senderAccountId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        String sender = senderAccountId == null || senderAccountId.isBlank()
                ? repository.findNearTransactionSender(CHAIN, txHash).orElseThrow()
                : senderAccountId;
        while (Instant.now().isBefore(deadline)) {
            JsonNode result = rpc.transactionStatus(txHash, sender);
            if (txSucceeded(result)) {
                return result;
            }
            JsonNode failure = result.path("status").path("Failure");
            if (!failure.isMissingNode()) {
                throw new IllegalStateException("NEAR transaction failed: " + failure);
            }
            sleep(750L);
        }
        throw new IllegalStateException("NEAR confirmation timeout for " + txHash);
    }

    private String publicKeyBase58(ChainAddressRecord from) {
        return keyService.publicKeyBase58(from.getUserId(), from.getBiz(), from.getAddressIndex());
    }

    private JsonNode sendFunctionCall(ChainAddressRecord from, String contractAccountId,
                                      String methodName, byte[] args, long gas, BigInteger depositYocto) {
        JsonNode accessKey = rpc.accessKey(from.getAddress(), publicKeyBase58(from));
        long nonce = accessKey.path("nonce").asLong(0L) + 1L;
        NearTransactionSigner.SignedTransaction signed = signer.functionCall(
                from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), nonce, contractAccountId, accessKey.path("block_hash").asText(),
                methodName, args, gas, depositYocto);
        return rpc.broadcastTxCommit(signed.signedTransactionBase64());
    }

    private void record(String hash, long actionIndex, String sender, String receiver,
                        String assetSymbol, BigDecimal amount,
                        long gasBurnt, long blockHeight, String status, String rawPayload) {
        repository.recordNearTransaction(NearTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(hash)
                .actionIndex(actionIndex)
                .sender(sender)
                .receiver(receiver)
                .assetSymbol(assetSymbol)
                .amount(amount)
                .gasBurnt(gasBurnt)
                .blockHeight(blockHeight)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }

    private static boolean txSucceeded(JsonNode result) {
        JsonNode status = result.path("status");
        return status.has("SuccessValue") || status.has("SuccessReceiptId");
    }

    private static long gasBurnt(JsonNode result) {
        long total = result.path("transaction_outcome").path("outcome").path("gas_burnt").asLong(0L);
        for (JsonNode receipt : result.path("receipts_outcome")) {
            total += receipt.path("outcome").path("gas_burnt").asLong(0L);
        }
        return total;
    }

    private static long blockHeight(JsonNode result) {
        long height = result.path("transaction_outcome").path("block_height").asLong(0L);
        for (JsonNode receipt : result.path("receipts_outcome")) {
            height = Math.max(height, receipt.path("block_height").asLong(0L));
        }
        return height;
    }

    public static BigInteger toYocto(BigDecimal amount) {
        return amount.movePointRight(DECIMALS).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    public static BigDecimal fromYocto(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount)
                .movePointLeft(DECIMALS)
                .stripTrailingZeros();
    }

    public static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private byte[] jsonBytes(JsonNode json) {
        try {
            return objectMapper.writeValueAsBytes(json);
        } catch (IOException e) {
            throw new IllegalStateException("NEAR JSON serialization failed", e);
        }
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
            throw new IllegalStateException("NEAR wait interrupted", e);
        }
    }
}
