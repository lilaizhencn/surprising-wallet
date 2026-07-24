package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Aptos 链交易服务，负责构造、签名、广播交易，以及提现/归集的确认流程。
 *
 * <p>支持的操作：
 * <ul>
 *   <li>APT 原生币发送</li>
 *   <li>FA 同质化资产发送</li>
 *   <li>通用 entry function 调用</li>
 *   <li>Move 合约发布</li>
 *   <li>提现（withdraw）和归集（collect）及对应的确认</li>
 * </ul></p>
 *
 * <p>所有写操作都需要通过 {@link WalletRuntimeConfigService} 的任务开关检查。</p>
 *
 * @see AptosTransactionSigner
 * @see AptosRpcClient
 */
@Service
@RequiredArgsConstructor
public class AptosTransactionService {

    /** 链标识常量 */
    private static final String CHAIN = "APTOS";

    /** Aptos RPC 客户端 */
    private final AptosRpcClient rpc;

    /** 交易签名器 */
    private final AptosTransactionSigner signer;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选，用于任务开关控制） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 发送 APT 原生转账（简化模式）。
     *
     * @param derivationIndex 派生索引
     * @param fromAddress     发送方地址
     * @param toAddress       接收方地址
     * @param amountOctas     转账金额（octas）
     * @return 交易哈希
     */
    public String sendNative(long derivationIndex, String fromAddress, String toAddress, long amountOctas) {
        return sendNative(0L, 0, derivationIndex, fromAddress, toAddress, amountOctas);
    }

    /**
     * 发送 APT 原生转账（基于地址记录）。
     *
     * @param from      发送方地址记录
     * @param toAddress 接收方地址
     * @param amountOctas 转账金额（octas）
     * @return 交易哈希
     */
    public String sendNative(ChainAddressRecord from, String toAddress, long amountOctas) {
        return sendNative(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                from.getAddress(), toAddress, amountOctas);
    }

    /**
     * 发送 APT 原生转账（完整模式）。
     * <p>先估算 Gas，再预留序列号，签名后广播，最后记录到数据库。</p>
     */
    private String sendNative(long userId, int biz, long derivationIndex,
                              String fromAddress, String toAddress, long amountOctas) {
        GasPlan gas = gasPlan();
        long chainSequence = rpc.sequenceNumber(fromAddress);
        long sequence = repository.reserveAccountSequence(CHAIN, AptosHex.normalizeAddress(fromAddress), chainSequence);
        AptosTransactionSigner.SignedTransaction tx = signer.nativeTransfer(
                userId, biz, derivationIndex, fromAddress, sequence, toAddress, amountOctas,
                gas.maxGasAmount(), gas.gasUnitPrice(), rpc.chainId());
        String hash = rpc.submitTransaction(tx.json());
        record(hash, fromAddress, toAddress, "APT", AptosRpcClient.aptCoinType(),
                BigDecimal.valueOf(amountOctas).movePointLeft(8),
                0L, gas.gasUnitPrice(),
                sequence, "SENT", tx.json().toString());
        return hash;
    }

    public String sendToken(ChainAddressRecord from, TokenDefinition token,
                            String toAddress, long amountAtomic) {
        return sendFungibleAsset(from, AptosFungibleAsset.requireMetadata(token), toAddress, amountAtomic);
    }

    public String sendFungibleAsset(ChainAddressRecord from, String metadataAddress,
                                    String toAddress, long amountAtomic) {
        TokenDefinition token = repository.findTokenByContract(CHAIN, metadataAddress)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unconfigured Aptos fungible asset " + metadataAddress));
        GasPlan gas = gasPlan();
        long chainSequence = rpc.sequenceNumber(from.getAddress());
        long sequence = repository.reserveAccountSequence(
                CHAIN, AptosHex.normalizeAddress(from.getAddress()), chainSequence);
        AptosTransactionSigner.SignedTransaction tx = signer.fungibleAssetTransfer(
                from.getUserId(), from.getBiz(), from.getAddressIndex(), from.getAddress(), sequence,
                metadataAddress, toAddress, amountAtomic,
                gas.maxGasAmount(), gas.gasUnitPrice(), rpc.chainId());
        String hash = rpc.submitTransaction(tx.json());
        record(hash, from.getAddress(), toAddress, token.getSymbol(),
                AptosFungibleAsset.requireMetadata(token),
                BigDecimal.valueOf(amountAtomic).movePointLeft(token.getDecimals()),
                0L, gas.gasUnitPrice(),
                sequence, "SENT", tx.json().toString());
        return hash;
    }

    public String runEntryFunction(long derivationIndex, String fromAddress,
                                   String module, String function,
                                   List<String> typeArguments,
                                   List<AptosTransactionSigner.FunctionArgument> arguments) {
        GasPlan gas = gasPlan();
        long chainSequence = rpc.sequenceNumber(fromAddress);
        long sequence = repository.reserveAccountSequence(CHAIN, AptosHex.normalizeAddress(fromAddress), chainSequence);
        AptosTransactionSigner.SignedTransaction tx = signer.entryFunction(
                derivationIndex, fromAddress, sequence, module, function,
                typeArguments, arguments, gas.maxGasAmount(), gas.gasUnitPrice(), rpc.chainId());
        return rpc.submitTransaction(tx.json());
    }

    public DeployPackageResult publishPackage(ChainAddressRecord publisher,
                                              byte[] metadata,
                                              List<byte[]> modules,
                                              long maxGasAmount,
                                              long gasUnitPrice) {
        if (metadata == null || metadata.length == 0) {
            throw new IllegalArgumentException("Aptos package metadata is required");
        }
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("Aptos package modules are required");
        }
        long chainSequence = rpc.sequenceNumber(publisher.getAddress());
        long sequence = repository.reserveAccountSequence(
                CHAIN, AptosHex.normalizeAddress(publisher.getAddress()), chainSequence);
        AptosTransactionSigner.SignedTransaction tx = signer.publishPackage(
                publisher.getUserId(),
                publisher.getBiz(),
                publisher.getAddressIndex(),
                publisher.getAddress(),
                sequence,
                metadata,
                modules,
                maxGasAmount,
                gasUnitPrice,
                rpc.chainId());
        String hash = rpc.submitTransaction(tx.json());
        record(hash, publisher.getAddress(), publisher.getAddress(), "APT_CONTRACT",
                AptosRpcClient.aptCoinType(), BigDecimal.ZERO,
                0L, gasUnitPrice, sequence, "SENT", tx.json().toString());
        return new DeployPackageResult(hash, sequence);
    }

    public String withdrawNative(String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amountOctas) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "aptos withdrawNative");
        Optional<String> existing = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long amount = amountOctas.longValueExact();
        long feeReserve = profile().getDefaultFee();
        if (repository.createWithdrawalOrder(orderNo, userId, CHAIN, "APT", toAddress,
                amountOctas, BigDecimal.valueOf(feeReserve)) == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Aptos withdrawal already claimed"));
        }
        BigDecimal debit = amountOctas.add(BigDecimal.valueOf(feeReserve));
        if (!repository.freezeLedgerBalance(CHAIN, "APT", from.getAccountId(), debit)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient APT ledger balance");
            throw new IllegalStateException("insufficient APT ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Aptos withdrawal is not signable: " + orderNo);
            }
            String hash = sendNative(from, toAddress, amount);
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), hash) != 1) {
                throw new IllegalStateException("Aptos withdrawal state changed before SENT: " + orderNo);
            }
            return hash;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    public String withdrawToken(String orderNo, long userId, ChainAddressRecord from,
                                String contractAddress, String toAddress, BigDecimal atomicAmount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "aptos withdrawToken");
        Optional<String> existing = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, contractAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Aptos token " + contractAddress));
        if (repository.createWithdrawalOrder(orderNo, userId, CHAIN, token.getSymbol(), toAddress,
                atomicAmount, BigDecimal.ZERO) == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Aptos token withdrawal already claimed"));
        }
        if (!repository.freezeLedgerBalance(CHAIN, token.getSymbol(), from.getAccountId(), atomicAmount)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient " + token.getSymbol() + " ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Aptos token withdrawal is not signable: " + orderNo);
            }
            String hash = sendToken(from, token, toAddress, atomicAmount.longValueExact());
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), hash) != 1) {
                throw new IllegalStateException("Aptos token withdrawal state changed before SENT: " + orderNo);
            }
            return hash;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountOctas) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "aptos collectNative");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Aptos collection is not retryable"));
        }
        try {
            String hash = sendNative(from, hotAddress, amountOctas.longValueExact());
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", hash, null, null);
            return hash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectToken(java.util.UUID tenantId, String collectionNo,
                               ChainAddressRecord from, String contractAddress,
                               String hotAddress, BigDecimal atomicAmount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "aptos collectToken");
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, contractAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Aptos token " + contractAddress));
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Aptos token collection is not retryable"));
        }
        try {
            String hash = sendToken(from, token, hotAddress, atomicAmount.longValueExact());
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", hash, null, null);
            return hash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmWithdrawal(String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        return confirmWithdrawal(repository.requireWithdrawalTenant(CHAIN, orderNo),
                orderNo, assetSymbol, accountId, debitAmount);
    }

    public boolean confirmWithdrawal(java.util.UUID tenantId, String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        String hash = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(hash, Duration.ofMinutes(2));
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, hash, assetSymbol, accountId, debitAmount)) {
            markConfirmed(hash, transaction);
            return true;
        }
        return false;
    }
    public boolean confirmCollection(java.util.UUID tenantId, String collectionNo) {
        String hash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        JsonNode transaction = requireSuccessfulConfirmation(hash, Duration.ofMinutes(2));
        if (repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, hash) == 1) {
            markConfirmed(hash, transaction);
            return true;
        }
        return false;
    }
    public JsonNode requireSuccessfulConfirmation(String hash, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode transaction = rpc.transactionByHash(hash);
            if (transaction != null && !transaction.isNull()) {
                if (transaction.path("success").asBoolean(false)) {
                    return transaction;
                }
                if (!transaction.path("vm_status").isMissingNode()) {
                    throw new IllegalStateException("Aptos transaction failed: "
                            + transaction.path("vm_status").asText());
                }
            }
            sleep(750L);
        }
        throw new IllegalStateException("Aptos confirmation timeout for " + hash);
    }
    private GasPlan gasPlan() {
        long gasUnitPrice = Math.max(1L, rpc.estimateGasPrice());
        long feeReserve = Math.max(1L, profile().getDefaultFee());
        long maxGasAmount = Math.max(50_000L, (feeReserve + gasUnitPrice - 1L) / gasUnitPrice);
        return new GasPlan(maxGasAmount, gasUnitPrice);
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

    private void record(String hash, String sender, String receiver, String symbol, String coinType,
                        BigDecimal amount, long gasUsed, long gasUnitPrice, long sequenceNumber,
                        String status, String rawPayload) {
        repository.recordAptosTransaction(AptosTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(hash)
                .sender(AptosHex.normalizeAddress(sender))
                .receiver(AptosHex.normalizeAddress(receiver))
                .assetSymbol(symbol)
                .coinType(coinType)
                .amount(amount)
                .gasUsed(gasUsed)
                .gasUnitPrice(gasUnitPrice)
                .sequenceNumber(sequenceNumber)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }
    private void markConfirmed(String hash, JsonNode transaction) {
        long version = transaction.path("version").asLong(0);
        long gasUsed = transaction.path("gas_used").asLong(0);
        long gasUnitPrice = transaction.path("gas_unit_price").asLong(0);
        repository.markAptosTransactionConfirmed(CHAIN, hash, version, gasUsed, gasUnitPrice,
                transaction.toString());
        String sender = transaction.path("sender").asText();
        if (!sender.isBlank()) {
            repository.synchronizeAccountSequence(CHAIN, AptosHex.normalizeAddress(sender),
                    transaction.path("sequence_number").asLong(0) + 1L);
        }
    }
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aptos wait interrupted", e);
        }
    }
    private record GasPlan(long maxGasAmount, long gasUnitPrice) {
    }
    public record DeployPackageResult(String txHash, long sequenceNumber) {
    }
}
