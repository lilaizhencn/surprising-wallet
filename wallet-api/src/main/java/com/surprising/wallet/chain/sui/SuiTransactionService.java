package com.surprising.wallet.chain.sui;

import tools.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.SuiTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sui 链交易服务，负责构造、签名、广播 PTB 交易，以及提现/归集流程。
 *
 * <p>核心流程：选取 coin 对象 -> 构造 PTB 交易 -> Blake2b 签名 -> gRPC 广播 -> 确认。
 * 支持 SUI 原生币和 Coin&lt;T&gt; 代币的发送、提现、归集。</p>
 *
 * @see SuiPtbTransactionBuilder
 * @see SuiTransactionSigner
 * @see SuiRpcClient
 */
@Service
public
class SuiTransactionService {

    /** 链标识常量 */
    private static final String CHAIN = "SUI";

    /** Sui gRPC 客户端 */
    private final SuiRpcClient rpc;

    /** 交易签名器 */
    private final SuiTransactionSigner signer;

    /** PTB 交易构造器 */
    private final SuiPtbTransactionBuilder ptbBuilder;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 构造 {@code SuiTransactionService}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public SuiTransactionService(SuiRpcClient rpc, SuiTransactionSigner signer,
                                 SuiPtbTransactionBuilder ptbBuilder, ChainJdbcRepository repository) {
        this.rpc = rpc;
        this.signer = signer;
        this.ptbBuilder = ptbBuilder;
        this.repository = repository;
    }

    /**
     * 构造 {@code SuiTransactionService}，初始化该组件运行所需的状态和依赖。
     */
    SuiTransactionService(SuiRpcClient rpc, SuiTransactionSigner signer, ChainJdbcRepository repository) {
        this(rpc, signer, new SuiPtbTransactionBuilder(), repository);
    }
    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public String sendNative(long derivationIndex, String fromAddress, String toAddress, long amountMist) {
        return sendNative(0L, 0, derivationIndex, fromAddress, toAddress, amountMist);
    }
    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public String sendNative(ChainAddressRecord from, String toAddress, long amountMist) {
        return sendNative(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), toAddress, amountMist);
    }

    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 发送或广播 {@code sendCoin} 对应的链上请求，并返回节点处理结果。
     */
    public String sendCoin(long derivationIndex, String fromAddress, String coinType,
                           String toAddress, long amountAtomic) {
        return sendCoin(0L, 0, derivationIndex, fromAddress, coinType, toAddress, amountAtomic);
    }
    /**
     * 发送或广播 {@code sendCoin} 对应的链上请求，并返回节点处理结果。
     */
    public String sendCoin(ChainAddressRecord from, String coinType, String toAddress, long amountAtomic) {
        return sendCoin(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), coinType, toAddress, amountAtomic);
    }

    /**
     * 发送或广播 {@code sendCoin} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 处理 {@code withdrawNative} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String withdrawNative(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "sui withdrawNative");
        Optional<String> existing = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeReserve = profile().getDefaultFee();
        int decimals = decimals("SUI");
        BigDecimal fee = BigDecimal.valueOf(feeReserve).movePointLeft(decimals);
        if (repository.createTenantWithdrawalOrder(tenantId, orderNo, userId, CHAIN, "SUI",
                from.getAddress(), from.getAccountId(), toAddress, amount, fee) == 0) {
            return repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Sui withdrawal already claimed"));
        }
        BigDecimal debit = amount.add(fee);
        if (!repository.freezeLedgerBalance(tenantId, CHAIN, "SUI", from.getAccountId(), debit)) {
            repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient SUI ledger balance");
            throw new IllegalStateException("insufficient SUI ledger balance");
        }
        repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Sui withdrawal is not signable: " + orderNo);
            }
            String digest = sendNative(from, toAddress, toAtomic(amount, decimals));
            if (repository.markWithdrawalSent(tenantId, CHAIN, orderNo, from.getAddress(), digest) != 1) {
                throw new IllegalStateException("Sui withdrawal state changed before SENT: " + orderNo);
            }
            record(digest, from.getAddress(), toAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE, amount,
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    /**
     * 处理 {@code withdrawCoin} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String withdrawCoin(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                               String coinType, String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "sui withdrawCoin");
        Optional<String> existing = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, coinType)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Sui coin " + coinType));
        if (repository.createTenantWithdrawalOrder(tenantId, orderNo, userId, CHAIN, token.getSymbol(),
                from.getAddress(), from.getAccountId(), toAddress, amount, BigDecimal.ZERO) == 0) {
            return repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Sui coin withdrawal already claimed"));
        }
        if (!repository.freezeLedgerBalance(tenantId, CHAIN, token.getSymbol(), from.getAccountId(), amount)) {
            repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient " + token.getSymbol() + " ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(tenantId, CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Sui coin withdrawal is not signable: " + orderNo);
            }
            String digest = sendCoin(from, coinType, toAddress, toAtomic(amount, token.getDecimals()));
            if (repository.markWithdrawalSent(tenantId, CHAIN, orderNo, from.getAddress(), digest) != 1) {
                throw new IllegalStateException("Sui coin withdrawal state changed before SENT: " + orderNo);
            }
            record(digest, from.getAddress(), toAddress, token.getSymbol(), coinType, amount,
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    /**
     * 处理 {@code collectNative} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountMist) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "sui collectNative");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeReserve = profile().getDefaultFee();
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Sui collection is not retryable"));
        }
        try {
            String digest = sendNative(from, hotAddress, amountMist.longValueExact());
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", digest, null, null);
            record(digest, from.getAddress(), hotAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE,
                    amountMist.movePointLeft(decimals("SUI")),
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 处理 {@code collectCoin} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectCoin(java.util.UUID tenantId, String collectionNo,
                              ChainAddressRecord from, String coinType,
                              String hotAddress, BigDecimal amountAtomic) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "sui collectCoin");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, coinType)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Sui coin " + coinType));
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Sui coin collection is not retryable"));
        }
        try {
            String digest = sendCoin(from, coinType, hotAddress, amountAtomic.longValueExact());
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", digest, null, null);
            record(digest, from.getAddress(), hotAddress, token.getSymbol(), coinType,
                    amountAtomic.movePointLeft(token.getDecimals()),
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmWithdrawal(UUID tenantId, String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        String digest = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(digest, Duration.ofMinutes(2));
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, digest, assetSymbol, accountId, debitAmount)) {
            markConfirmed(digest, transaction);
            return true;
        }
        return false;
    }
    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmCollection(java.util.UUID tenantId, String collectionNo) {
        String digest = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(digest, Duration.ofMinutes(2));
        if (repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, digest) == 1) {
            markConfirmed(digest, transaction);
            return true;
        }
        return false;
    }
    /**
     * 校验 {@code requireSuccessfulConfirmation} 对应的前置条件，不满足时抛出明确异常。
     */
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
    /**
     * 获取或查询 {@code selectCoins} 对应的数据，并向调用方返回当前业务状态。
     */
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
    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
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
     * 记录或保存 {@code record} 对应的数据，并遵守幂等和事务约束。
     */
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
    /**
     * 写入或更新 {@code markConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private void markConfirmed(String digest, JsonNode transaction) {
        long gasUsed = totalGas(transaction.path("effects").path("gasUsed"));
        long checkpoint = transaction.path("checkpoint").asLong(0L);
        repository.markSuiTransactionConfirmed(CHAIN, digest, checkpoint, gasUsed, transaction.toString());
    }
    /**
     * 编码 {@code totalGas} 对应的数据，生成链上或接口所需的表示。
     */
    private long totalGas(JsonNode gas) {
        return gas.path("computationCost").asLong(0)
                + gas.path("storageCost").asLong(0)
                - gas.path("storageRebate").asLong(0);
    }
    /**
     * 获取或查询 {@code decimals} 对应的数据，并向调用方返回当前业务状态。
     */
    private int decimals(String symbol) {
        return repository.findAsset(CHAIN, symbol)
                .map(asset -> asset.getDecimals())
                .orElseThrow(() -> new IllegalStateException("missing Sui asset configuration: " + symbol));
    }
    /**
     * 编码 {@code toAtomic} 对应的数据，生成链上或接口所需的表示。
     */
    private long toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).longValueExact();
    }
    /**
     * 转换或计算 {@code sleep} 对应的值，统一金额、格式和边界规则。
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui wait interrupted", e);
        }
    }

}
