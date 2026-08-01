package com.surprising.wallet.chain.near;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.chain.model.NearTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.repository.ChainJdbcRepository;
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

/**
 * NEAR 链交易服务，负责构造、签名、广播 Borsh 序列化交易及提现/归集流程。
 *
 * <p>支持的操作：
 * <ul>
 *   <li>NEAR 原生转账（Transfer Action）</li>
 *   <li>隐式账户激活</li>
 *   <li>NEP-141 代币转账（FunctionCall ft_transfer）</li>
 *   <li>代币存储注册（storage_deposit）</li>
 *   <li>合约部署并初始化</li>
 *   <li>提现和归集确认</li>
 * </ul></p>
 *
 * <p>NEAR 精度：1 NEAR = 10^24 yoctoNEAR。默认使用 24 位小数。</p>
 *
 * @see NearTransactionSigner
 * @see NearRpcClient
 * @see NearKeyService
 */
@Service
public
class NearTransactionService {

    /** 链标识 */
    private static final String CHAIN = "NEAR";

    /** 原生币符号 */
    private static final String SYMBOL = "NEAR";

    /** NEAR 小数位数（1 NEAR = 10^24 yoctoNEAR） */
    private static final int DECIMALS = 24;

    /** 默认 FunctionCall Gas 用量（30 TGas） */
    private static final long DEFAULT_FUNCTION_CALL_GAS = 30_000_000_000_000L;

    /** 代币转账 Gas 用量（15 TGas） */
    private static final long TOKEN_TRANSFER_GAS = 15_000_000_000_000L;

    /** 1 yoctoNEAR，用于 ft_transfer 调用时的最小 deposit */
    private static final BigInteger ONE_YOCTO = BigInteger.ONE;

    /** NEAR RPC 客户端 */
    private final NearRpcClient rpc;

    /** 交易签名器 */
    private final NearTransactionSigner signer;

    /** NEAR 密钥服务 */
    private final NearKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 构造 {@code NearTransactionService}，初始化该组件运行所需的状态和依赖。
     */
    public NearTransactionService(NearRpcClient rpc,
                                  NearTransactionSigner signer,
                                  NearKeyService keyService,
                                  ChainJdbcRepository repository) {
        this.rpc = rpc;
        this.signer = signer;
        this.keyService = keyService;
        this.repository = repository;
    }
    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
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
    /**
     * 执行 {@code accountExists} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean accountExists(String accountId) {
        return rpc.accountExists(accountId);
    }
    /**
     * 执行 {@code activateImplicitAccount} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String activateImplicitAccount(ChainAddressRecord payer, String accountId, BigInteger amountYocto) {
        if (!NearKeyService.isValidAccountId(accountId)) {
            throw new IllegalArgumentException("invalid NEAR account id: " + accountId);
        }
        if (amountYocto == null || amountYocto.signum() <= 0) {
            throw new IllegalArgumentException("NEAR activation amount must be positive");
        }
        return sendNative(payer, accountId, amountYocto);
    }
    /**
     * 发送或广播 {@code sendToken} 对应的链上请求，并返回节点处理结果。
     */
    public String sendToken(ChainAddressRecord from, TokenDefinition token, String toAccountId, BigDecimal amount) {
        BigInteger atomicAmount = toAtomic(amount, token.getDecimals());
        ObjectNode args = objectMapper.createObjectNode();
        args.put("receiver_id", toAccountId);
        args.put("amount", atomicAmount.toString());
        JsonNode result = sendFunctionCall(from, token.getContractAddress(), "ft_transfer",
                jsonBytes(args), TOKEN_TRANSFER_GAS, ONE_YOCTO);
        String txHash = result.path("transaction").path("hash").asText();
        record(txHash, 0L, from.getAddress(), toAccountId, token.getSymbol(), amount,
                gasBurnt(result), blockHeight(result), txSucceeded(result) ? "CONFIRMED" : "SENT",
                result.toString());
        return txHash;
    }

    /**
     * 执行 {@code storageDeposit} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 执行 {@code deployContractAndInit} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public DeployResult deployContractAndInit(ChainAddressRecord from, byte[] contractCode,
                                              String initMethod, byte[] initArgs,
                                              long gas, BigInteger depositYocto) {
        JsonNode accessKey = rpc.accessKey(from.getAddress(), publicKeyBase58(from));
        long nonce = accessKey.path("nonce").asLong(0L) + 1L;
        NearTransactionSigner.SignedTransaction signed = signer.deployContractAndFunctionCall(
                from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), nonce, from.getAddress(),
                accessKey.path("block_hash").asText(), contractCode, initMethod,
                initArgs, gas, depositYocto);
        JsonNode result = rpc.broadcastTxCommit(signed.signedTransactionBase64());
        String txHash = result.path("transaction").path("hash").asText(signed.transactionHash());
        record(txHash, 0L, from.getAddress(), from.getAddress(), "NEAR_CONTRACT", BigDecimal.ZERO,
                gasBurnt(result), blockHeight(result), txSucceeded(result) ? "CONFIRMED" : "SENT",
                result.toString());
        return new DeployResult(txHash, nonce, result);
    }
    /**
     * 编码 {@code tokenStorageRegistered} 对应的数据，生成链上或接口所需的表示。
     */
    public boolean tokenStorageRegistered(TokenDefinition token, String accountId) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("account_id", accountId);
        JsonNode balance = rpc.viewFunctionJson(token.getContractAddress(), "storage_balance_of", jsonBytes(args));
        return balance != null && !balance.isNull() && !balance.isMissingNode();
    }
    /**
     * 编码 {@code tokenStorageMinimum} 对应的数据，生成链上或接口所需的表示。
     */
    public BigInteger tokenStorageMinimum(TokenDefinition token) {
        JsonNode bounds = rpc.viewFunctionJson(token.getContractAddress(), "storage_balance_bounds",
                "{}".getBytes(StandardCharsets.UTF_8));
        String min = bounds.path("min").asText("0");
        return new BigInteger(min);
    }

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String txHash,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        JsonNode status = requireSuccessfulConfirmation(txHash, debitAccountId, Duration.ofMinutes(2));
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, txHash, assetSymbol, debitAccountId, debitAmount)) {
            repository.markNearTransactionConfirmed(CHAIN, txHash, blockHeight(status), gasBurnt(status),
                    status.toString());
            return true;
        }
        return false;
    }

    /**
     * 处理 {@code collectNative} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger amountYocto) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "near collectNative");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("NEAR collection is not retryable"));
        }
        try {
            String txHash = sendNative(from, hotAddress, amountYocto);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 处理 {@code collectToken} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectToken(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "near collectToken");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("NEAR token collection is not retryable"));
        }
        try {
            ensureTokenStorageRegistered(token, hotAddress);
            String txHash = sendToken(from, token, hotAddress, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    /**
     * 校验 {@code ensureTokenStorageRegistered} 对应的输入或状态，失败时抛出明确异常。
     */
    private void ensureTokenStorageRegistered(TokenDefinition token, String accountId) {
        if (tokenStorageRegistered(token, accountId)) {
            return;
        }
        ChainAddressRecord payer = repository.findChainAddress(CHAIN, SYMBOL,
                        HotWalletRules.DEFAULT_HOT_USER_ID,
                        HotWalletRules.DEFAULT_HOT_BIZ,
                        HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                        HotWalletRules.DEFAULT_HOT_WALLET_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "missing NEAR hot wallet for token storage registration"));
        BigInteger minimum = tokenStorageMinimum(token);
        if (minimum.signum() <= 0) {
            throw new IllegalStateException("NEAR token storage minimum is not configured by contract");
        }
        String txHash = storageDeposit(payer, token, accountId, minimum);
        requireSuccessfulConfirmation(txHash, payer.getAddress(), Duration.ofMinutes(2));
        if (!waitForTokenStorageRegistered(token, accountId, Duration.ofSeconds(30))) {
            throw new IllegalStateException("NEAR token storage registration did not complete");
        }
    }
    /**
     * 处理 {@code waitForTokenStorageRegistered} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean waitForTokenStorageRegistered(TokenDefinition token, String accountId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (tokenStorageRegistered(token, accountId)) {
                return true;
            }
            sleep(750L);
        }
        return tokenStorageRegistered(token, accountId);
    }

    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(txHash, null, Duration.ofMinutes(2));
        if (repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1) {
            repository.markNearTransactionConfirmed(CHAIN, txHash, blockHeight(status), gasBurnt(status),
                    status.toString());
            return true;
        }
        return false;
    }
    /**
     * 校验 {@code requireSuccessfulConfirmation} 对应的前置条件，不满足时抛出明确异常。
     */
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
    /**
     * 执行 {@code publicKeyBase58} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String publicKeyBase58(ChainAddressRecord from) {
        return keyService.publicKeyBase58(from.getUserId(), from.getBiz(), from.getAddressIndex());
    }

    /**
     * 发送或广播 {@code sendFunctionCall} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 记录或保存 {@code record} 对应的数据，并遵守幂等和事务约束。
     */
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
    /**
     * 执行 {@code txSucceeded} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static boolean txSucceeded(JsonNode result) {
        JsonNode status = result.path("status");
        return status.has("SuccessValue") || status.has("SuccessReceiptId");
    }
    /**
     * 执行 {@code gasBurnt} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static long gasBurnt(JsonNode result) {
        long total = result.path("transaction_outcome").path("outcome").path("gas_burnt").asLong(0L);
        for (JsonNode receipt : result.path("receipts_outcome")) {
            total += receipt.path("outcome").path("gas_burnt").asLong(0L);
        }
        return total;
    }
    /**
     * 执行 {@code blockHeight} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static long blockHeight(JsonNode result) {
        long height = result.path("transaction_outcome").path("block_height").asLong(0L);
        for (JsonNode receipt : result.path("receipts_outcome")) {
            height = Math.max(height, receipt.path("block_height").asLong(0L));
        }
        return height;
    }
    /**
     * 编码 {@code toYocto} 对应的数据，生成链上或接口所需的表示。
     */
    public static BigInteger toYocto(BigDecimal amount) {
        return amount.movePointRight(DECIMALS).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    /**
     * 解析 {@code fromYocto} 对应的输入，并转换为当前业务模型。
     */
    public static BigDecimal fromYocto(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount)
                .movePointLeft(DECIMALS)
                .stripTrailingZeros();
    }
    /**
     * 编码 {@code toAtomic} 对应的数据，生成链上或接口所需的表示。
     */
    public static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    /**
     * 编码 {@code jsonBytes} 对应的数据，生成链上或接口所需的表示。
     */
    private byte[] jsonBytes(JsonNode json) {
        try {
            return objectMapper.writeValueAsBytes(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("NEAR JSON serialization failed", e);
        }
    }
    /**
     * 校验 {@code requireTaskEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    /**
     * 转换或计算 {@code sleep} 对应的值，统一金额、格式和边界规则。
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NEAR wait interrupted", e);
        }
    }
    public record DeployResult(String txHash, long nonce, JsonNode result) {
    }
}
