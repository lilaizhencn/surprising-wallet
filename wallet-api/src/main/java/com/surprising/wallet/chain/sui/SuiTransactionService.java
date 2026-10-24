package com.surprising.wallet.chain.sui;

import com.surprising.wallet.chain.model.SuiTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.SuiTransactionRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.service.WithdrawalClaimService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

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
public class SuiTransactionService {

    /** 链标识常量 */
    private static final String CHAIN = "SUI";

    /** Sui gRPC 客户端 */
    private final SuiRpcClient rpc;

    /** 交易签名器 */
    private final SuiTransactionSigner signer;

    /** PTB 交易构造器 */
    private final SuiPtbTransactionBuilder ptbBuilder;
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final LedgerBalanceRepository ledgerBalanceRepository;
    private final WithdrawalClaimService withdrawalClaimService;
    private final ChainProfileRepository chainProfileRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final CollectionRecordRepository collectionRecordRepository;
    private final SuiTransactionRepository suiTransactionRepository;
    private final ChainAssetRepository chainAssetRepository;


    private final WithdrawalSettlementService settlements;

    /** 运行时任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;

    public SuiTransactionService(
            SuiRpcClient rpc,
            SuiTransactionSigner signer,
            SuiPtbTransactionBuilder ptbBuilder,
            WithdrawalOrderRepository withdrawalOrderRepository,
            LedgerBalanceRepository ledgerBalanceRepository,
            WithdrawalClaimService withdrawalClaimService,
            ChainProfileRepository chainProfileRepository,
            TokenConfigRepository tokenConfigRepository,
            CollectionRecordRepository collectionRecordRepository,
            SuiTransactionRepository suiTransactionRepository,
            ChainAssetRepository chainAssetRepository,
            WithdrawalSettlementService settlements,
            WalletRuntimeConfigService runtimeConfigService) {
        this.rpc = rpc;
        this.signer = signer;
        this.ptbBuilder = ptbBuilder;
        this.withdrawalOrderRepository = withdrawalOrderRepository;
        this.ledgerBalanceRepository = ledgerBalanceRepository;
        this.withdrawalClaimService = withdrawalClaimService;
        this.chainProfileRepository = chainProfileRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.collectionRecordRepository = collectionRecordRepository;
        this.suiTransactionRepository = suiTransactionRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.settlements = settlements;
        this.runtimeConfigService = java.util.Objects.requireNonNull(runtimeConfigService);
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

    public String withdrawNative(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amount) {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_WITHDRAW, "sui withdrawNative");
        Optional<String> existing = withdrawalOrderRepository.findTxHash(CHAIN, orderNo, tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeReserve = profile().getDefaultFee();
        int decimals = decimals("SUI");
        BigDecimal fee = BigDecimal.valueOf(feeReserve).movePointLeft(decimals);
        if (withdrawalOrderRepository.createForTenant(
                tenantId,
                orderNo,
                userId,
                CHAIN,
                "SUI",
                from.getAddress(),
                from.getAccountId(),
                toAddress,
                amount,
                fee) == 0) {
            return withdrawalOrderRepository.findTxHash(CHAIN, orderNo, tenantId)
                    .orElseThrow(() -> new IllegalStateException("Sui withdrawal already claimed"));
        }
        BigDecimal debit = amount.add(fee);
        if (!ledgerBalanceRepository.freeze(CHAIN, "SUI", from.getAccountId(), debit, tenantId)) {
            withdrawalOrderRepository.updateStatus(tenantId, CHAIN, orderNo, "FAILED", from.getAddress(), null, "insufficient SUI ledger balance");
            throw new IllegalStateException("insufficient SUI ledger balance");
        }
        withdrawalOrderRepository.updateStatus(tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (withdrawalClaimService.claim(tenantId, CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Sui withdrawal is not signable: " + orderNo);
            }
            String digest = sendNative(from, toAddress, toAtomic(amount, decimals));
            if (withdrawalOrderRepository.markSent(tenantId, CHAIN, orderNo, from.getAddress(), digest) != 1) {
                throw new IllegalStateException("Sui withdrawal state changed before SENT: " + orderNo);
            }
            record(digest, from.getAddress(), toAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE, amount,
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            withdrawalOrderRepository.markBroadcastUnknown(tenantId, CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    public String withdrawCoin(UUID tenantId, String orderNo, long userId, ChainAddressRecord from,
                               String coinType, String toAddress, BigDecimal amount) {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_WITHDRAW, "sui withdrawCoin");
        Optional<String> existing = withdrawalOrderRepository.findTxHash(CHAIN, orderNo, tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = chainProfileRepository.findAccountByChain(CHAIN).flatMap( configuredNetworkProfile -> tokenConfigRepository.findDefinitionByContract(
                CHAIN,
                configuredNetworkProfile.getNetwork(),
                coinType))
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Sui coin " + coinType));
        if (withdrawalOrderRepository.createForTenant(
                tenantId,
                orderNo,
                userId,
                CHAIN,
                token.getSymbol(),
                from.getAddress(),
                from.getAccountId(),
                toAddress,
                amount,
                BigDecimal.ZERO) == 0) {
            return withdrawalOrderRepository.findTxHash(CHAIN, orderNo, tenantId)
                    .orElseThrow(() -> new IllegalStateException("Sui coin withdrawal already claimed"));
        }
        if (!ledgerBalanceRepository.freeze(CHAIN, token.getSymbol(), from.getAccountId(), amount, tenantId)) {
            withdrawalOrderRepository.updateStatus(
                    tenantId,
                    CHAIN,
                    orderNo,
                    "FAILED",
                    from.getAddress(),
                    null,
                    "insufficient " + token.getSymbol() + " ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        withdrawalOrderRepository.updateStatus(tenantId, CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (withdrawalClaimService.claim(tenantId, CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Sui coin withdrawal is not signable: " + orderNo);
            }
            String digest = sendCoin(from, coinType, toAddress, toAtomic(amount, token.getDecimals()));
            if (withdrawalOrderRepository.markSent(tenantId, CHAIN, orderNo, from.getAddress(), digest) != 1) {
                throw new IllegalStateException("Sui coin withdrawal state changed before SENT: " + orderNo);
            }
            record(digest, from.getAddress(), toAddress, token.getSymbol(), coinType, amount,
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            withdrawalOrderRepository.markBroadcastUnknown(tenantId, CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountMist) {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION, "sui collectNative");
        Optional<String> existing = collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long feeReserve = profile().getDefaultFee();
        if (collectionRecordRepository.claimSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Sui collection is not retryable"));
        }
        try {
            String digest = sendNative(from, hotAddress, amountMist.longValueExact());
            collectionRecordRepository.updateStatus(tenantId, CHAIN, collectionNo, "SENT", digest, null, null);
            record(digest, from.getAddress(), hotAddress, "SUI", SuiRpcClient.SUI_COIN_TYPE,
                    amountMist.movePointLeft(decimals("SUI")),
                    feeReserve, "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            collectionRecordRepository.updateStatus(tenantId, CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectCoin(java.util.UUID tenantId, String collectionNo,
                              ChainAddressRecord from, String coinType,
                              String hotAddress, BigDecimal amountAtomic) {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION, "sui collectCoin");
        Optional<String> existing = collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = chainProfileRepository.findAccountByChain(CHAIN).flatMap( configuredNetworkProfile -> tokenConfigRepository.findDefinitionByContract(
                CHAIN,
                configuredNetworkProfile.getNetwork(),
                coinType))
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Sui coin " + coinType));
        if (collectionRecordRepository.claimSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Sui coin collection is not retryable"));
        }
        try {
            String digest = sendCoin(from, coinType, hotAddress, amountAtomic.longValueExact());
            collectionRecordRepository.updateStatus(tenantId, CHAIN, collectionNo, "SENT", digest, null, null);
            record(digest, from.getAddress(), hotAddress, token.getSymbol(), coinType,
                    amountAtomic.movePointLeft(token.getDecimals()),
                    profile().getDefaultFee(), "SENT", null);
            return digest;
        } catch (RuntimeException e) {
            collectionRecordRepository.updateStatus(tenantId, CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmWithdrawal(UUID tenantId, String orderNo) {
        String digest = withdrawalOrderRepository.findTxHash(CHAIN, orderNo, tenantId).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(digest, Duration.ofMinutes(2));
        if (settlements.confirm(tenantId, CHAIN, orderNo, digest)) {
            markConfirmed(digest, transaction);
            return true;
        }
        return false;
    }
    public boolean confirmCollection(java.util.UUID tenantId, String collectionNo) {
        String digest = collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(digest, Duration.ofMinutes(2));
        if (collectionRecordRepository.markConfirmed(tenantId, CHAIN, collectionNo, digest) == 1) {
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
        return chainProfileRepository.findAccountByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private void record(String digest, String sender, String receiver, String symbol, String coinType,
                        BigDecimal amount, long feeReserve, String status, String rawPayload) {
        suiTransactionRepository.upsert(SuiTransactionRecord.builder()
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
        suiTransactionRepository.markConfirmed(CHAIN, digest, checkpoint, gasUsed, transaction.toString());
    }
    private long totalGas(JsonNode gas) {
        return gas.path("computationCost").asLong(0)
                + gas.path("storageCost").asLong(0)
                - gas.path("storageRebate").asLong(0);
    }
    private int decimals(String symbol) {
        return chainAssetRepository.findActiveDefinition(CHAIN, symbol)
                .map(asset -> asset.getDecimals())
                .orElseThrow(() -> new IllegalStateException("missing Sui asset configuration: " + symbol));
    }
    private long toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).longValueExact();
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
