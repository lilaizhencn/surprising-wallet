package com.surprising.wallet.coordinator;

import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.math.BigDecimal;
import com.surprising.wallet.chain.model.ChainAsset;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702ReceiptParser;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.service.CustodyGasAccountingService;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.Evm7702ConfigurationService;
import com.surprising.wallet.repository.Evm7702ConfigRepository.Configuration;
import com.surprising.wallet.chain.model.EvmBroadcastAttempt;
import lombok.RequiredArgsConstructor;
import com.surprising.wallet.chain.model.CustodyAddressRecord;

/**
 * EIP-7702 归集批次事务：领取、签名事实、状态转换与资金结算共同提交或回滚。
 */
@Component
@RequiredArgsConstructor
public class Evm7702CollectionCoordinator {

    private final Evm7702AccountRepository accountRepository;
    private final ChainAddressRepository chainAddressRepository;
    private final ChainProfileRepository chainProfileRepository;
    private final EvmCollectionBatchRepository batchRepository;
    private final EvmCollectionBatchItemRepository batchItemRepository;
    private final EvmCollectionBatchAttemptRepository batchAttemptRepository;
    private final CollectionRecordRepository collectionRecordRepository;
    private final CustodyAddressRepository custodyAddressRepository;
    private final CustodyGasAccountRepository gasAccountRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final CustodyGasAccountingService gasAccounting;
    private final EvmNonceRepository nonces;
    private final Evm7702ConfigurationService configurations;
    private final Evm7702ConfigRepository configRepository;

    /**
     * 预留中继 nonce，持久化已签名归集交易，并为该批次预留租户 Gas。
     */
    @Transactional(rollbackFor = Throwable.class)
    public Evm7702BatchTransactionService.SignedBatchTransaction persistSignedAttempt(
            Batch batch,
            String relayerAddress,
            BigInteger rpcPendingNonce,
            SignedAttemptFactory factory) {
        BigInteger reservedNonce = nonces.reserve(batch.chain(), relayerAddress.toLowerCase(), rpcPendingNonce);
        SignedAttempt signedAttempt = factory.create(reservedNonce);
        if (!reservedNonce.equals(signedAttempt.attempt().relayerNonce())) {
            throw new IllegalStateException("signed EIP-7702 attempt uses an unreserved relayer nonce");
        }
        saveSignedAttempt(batch, signedAttempt.attempt());
        gasAccounting.reserve(batch.tenantId(), "COLLECTION_BATCH", batch.id(), signedAttempt.reservedFee());
        return signedAttempt.signedTransaction();
    }

    /**
     * 校验归集回执并完成批次、原生费用和租户 Gas 使用量的最终结算。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void complete(
            String chain,
            PendingBatch batch,
            String txHash,
            BigInteger gasUsed,
            BigInteger effectiveGasPrice,
            BigInteger l2Fee,
            BigInteger l1Fee,
            BigInteger operatorFee,
            BigInteger blockNumber,
            String blockHash,
            List<Evm7702ReceiptParser.ItemResult> results) {
        BigDecimal actualFee = actualFee(chain, l2Fee, l1Fee, operatorFee);
        var batchRow = batchRepository.find(batch.tenantId(), batch.batchId()).orElseThrow();
        var config = configRepository.find(
                batchRow.chain(),
                batchRow.network(),
                null,
                batchRow.delegateVersion()).getFirst();
        int version = config.version();
        var expected = batchItemRepository.listIdentities(batch.tenantId(), batch.batchId());
        if (expected.size() != results.size()) {
            throw new IllegalStateException("receipt result count does not match batch");
        }
        int failures = 0;
        for (int index = 0; index < results.size(); index++) {
            var identity = expected.get(index);
            var result = results.get(index);
            if (identity.itemIndex() != result.itemIndex() ||
                    !identity.authority().equalsIgnoreCase(result.authority()) ||
                    !identity.token().equalsIgnoreCase(result.token()) ||
                    !identity.recipient().equalsIgnoreCase(result.recipient()) ||
                    !identity.amount().equals(result.requestedAmount())) {
                throw new IllegalStateException("receipt item identity does not match persisted batch");
            }
            String itemStatus = result.success() ? "CONFIRMED" : "RETRYABLE";
            if (!result.success()) {
                failures++;
            }
            if (batchItemRepository.complete(
                    batch.tenantId(),
                    batch.batchId(),
                    index,
                    result.actualReceived(),
                    itemStatus,
                    (long) result.logIndex(),
                    result.success() ? null : result.errorHash()) != 1) {
                throw new IllegalStateException("batch item completion transition failed");
            }
            String collectionStatus = result.success() ? "CONFIRMED" : "RETRYING";
            var itemRow = batchItemRepository.findForCompletion(
                    batch.tenantId(),
                    batch.batchId(),
                    index).orElseThrow();
            long collectionId = itemRow.collectionRecordId();
            collectionRecordRepository.updateExecution(
                    batch.tenantId(),
                    collectionId,
                    collectionStatus,
                    txHash,
                    result.success() ? null : "EIP-7702 item execution failed: " + result.errorHash());
            if (accountRepository.markCollectionCompleted(
                    batch.tenantId(),
                    itemRow.custodyAddressId(),
                    Boolean.TRUE.equals(itemRow.authorizationIncluded()),
                    txHash,
                    config.delegateAddress(),
                    version,
                    itemRow.operationNonce().longValue()) != 1) {
                throw new IllegalStateException("EIP-7702 account projection completion failed");
            }
        }
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        String batchStatus = failures == 0 ? "CONFIRMED" : failures == results.size() ? "FAILED" : "PARTIAL_FAILED";
        if (batchRepository.complete(
                batch.tenantId(),
                batch.batchId(),
                txHash,
                batchStatus,
                gasUsed,
                effectiveGasPrice,
                l2Fee,
                l1Fee,
                operatorFee,
                totalFee,
                actualFee,
                blockNumber,
                blockHash) != 1) {
            throw new IllegalStateException("batch completion transition failed");
        }
        batchAttemptRepository.markConfirmed(batch.tenantId(), batch.batchId(), txHash);
        gasAccounting.settle(batch.tenantId(), "COLLECTION_BATCH", batch.batchId(), actualFee, "EVM_RECEIPT", txHash);
    }

    /**
     * 按链上原子费用和数据库中的原生资产精度计算账务费用。
     */
    private BigDecimal actualFee(String chain, BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee) {
        var profile = chainProfileRepository.findAccountByChain(chain).orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chain));
        var asset = chainAssetRepository.findActiveDefinition(
                chain,
                profile.getNativeSymbol()).orElseThrow(() -> new IllegalStateException("missing active native chain_asset for " + chain));
        if (!Boolean.TRUE.equals(asset.getNativeAsset()) || asset.getDecimals() == null) {
            throw new IllegalStateException("invalid native chain_asset for " + chain);
        }
        return EvmFeeSupport.atomicToNative(l2Fee.add(l1Fee).add(operatorFee), asset.getDecimals(), java.math.RoundingMode.UP);
    }

    @Transactional(rollbackFor = Throwable.class)
    public Optional<Batch> claimNextBatch(String chain, String network) {
        var resolved = configurations.requireActive(chain, network);
        Configuration config = resolved.config();
        List<ChainAsset> assets = chainAssetRepository.listActiveByChain(chain);
        List<TokenConfiguration> tokenConfigs = tokenConfigRepository.listCollectEnabled(chain, network);
        CandidateGroup group = null;
        for (var record : collectionRecordRepository.listClaimable(chain, 500)) {
            UUID tenantId = record.getTenantId();
            UUID custodyId = record.getCustodyAddressId();
            String assetSymbol = String.valueOf(record.getAssetSymbol());
            ChainAsset asset = assets.stream().filter(row -> assetSymbol.equalsIgnoreCase(row.getSymbol())).findFirst().orElse(null);
            if (asset == null) {
                continue;
            }
            var custody = custodyAddressRepository.findFullByTenantAndId(tenantId, custodyId).orElse(null);
            if (custody == null || !"ACTIVE".equals(custody.status())) {
                continue;
            }
            if (!accountRepository.exists(tenantId, custodyId, chain, network)) {
                continue;
            }
            var hot = findHotAddress(tenantId, chain, String.valueOf(record.getToAddress()));
            if (hot == null) {
                continue;
            }
            boolean nativeAsset = Boolean.TRUE.equals(asset.getNativeAsset());
            TokenConfiguration token = tokenConfigs.stream().filter(row -> assetSymbol.equalsIgnoreCase(row.symbol())).findFirst().orElse(null);
            if ((!nativeAsset && token == null) || (nativeAsset && !config.nativeCollectionEnabled())) {
                continue;
            }
            group = new CandidateGroup(
                    tenantId,
                    assetSymbol,
                    String.valueOf(record.getToAddress()),
                    nativeAsset ? "0x0000000000000000000000000000000000000000" : token.contractAddress(),
                    asset.getDecimals());
            break;
        }
        if (group == null) {
            return Optional.empty();
        }
        List<ClaimedItem> items = new ArrayList<>();
        for (var record : collectionRecordRepository.listClaimable(chain, config.maxBatchItems())) {
            if (!group.tenantId().equals(record.getTenantId()) ||
                    !group.assetSymbol().equalsIgnoreCase(String.valueOf(record.getAssetSymbol())) ||
                    !group.hotWallet().equalsIgnoreCase(String.valueOf(record.getToAddress()))) {
                continue;
            }
            UUID custodyId = record.getCustodyAddressId();
            if (!accountRepository.exists(group.tenantId(), custodyId, chain, network)) {
                continue;
            }
            var custody = custodyAddressRepository.findFullByTenantAndId(group.tenantId(), custodyId).orElse(null);
            if (custody == null || !"ACTIVE".equals(custody.status())) {
                continue;
            }
            var address = chainAddressRepository.findOwned(
                    group.tenantId(),
                    custody.chainAddressId()).orElse(null);
            if (address == null) {
                continue;
            }
            BigDecimal amount = record.getAmount();
            BigInteger atomic;
            try {
                atomic = amount.movePointRight(group.decimals()).toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new IllegalStateException("collection amount has more precision than token decimals", e);
            }
            if (atomic.signum() <= 0) {
                throw new IllegalStateException("collection amount must be positive");
            }
            items.add(new ClaimedItem(
                    record.getId(),
                    record.getCollectionNo(),
                    group.tenantId(),
                    custodyId,
                    record.getFromAddress(),
                    record.getToAddress(),
                    amount,
                    atomic,
                    address));
            if (items.size() >= config.maxBatchItems()) {
                break;
            }
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }
        for (ClaimedItem item : items) {
            if (!item.tenantId().equals(group.tenantId())) {
                throw new IllegalStateException("cross-tenant collection batch is forbidden");
            }
        }
        UUID batchId = UUID.randomUUID();
        String batchHash = Numeric.toHexString(Hash.sha3((group.tenantId() + ":" + batchId).getBytes(StandardCharsets.UTF_8)));
        batchRepository.insert(
                batchId,
                group.tenantId(),
                chain,
                network,
                group.assetSymbol(),
                group.tokenContract(),
                group.decimals(),
                group.hotWallet(),
                config.relayerAddress(),
                config.version(),
                batchHash,
                items.size());
        Instant signatureDeadline = Instant.now().plusSeconds(config.signatureTtlSeconds());
        for (int index = 0; index < items.size(); index++) {
            ClaimedItem item = items.get(index);
            if (batchItemRepository.insert(
                    UUID.randomUUID(),
                    group.tenantId(),
                    batchId,
                    index,
                    item.collectionRecordId(),
                    item.custodyAddressId(),
                    item.fromAddress(),
                    group.tokenContract(),
                    group.hotWallet(),
                    item.amountAtomic(),
                    Timestamp.from(signatureDeadline)) != 1) {
                throw new IllegalStateException("collection batch item insert failed");
            }
            if (collectionRecordRepository.claimSigning(group.tenantId(), item.collectionRecordId()) != 1) {
                throw new IllegalStateException("collection item claim lost");
            }
        }
        return Optional.of(new Batch(
                batchId,
                group.tenantId(),
                chain,
                network,
                group.assetSymbol(),
                group.tokenContract(),
                group.decimals(),
                group.hotWallet(),
                batchHash,
                signatureDeadline,
                config,
                resolved.relayer(),
                List.copyOf(items)));
    }

    private void saveSignedAttempt(Batch batch, PreparedAttempt attempt) {
        if (!batch.tenantId().equals(attempt.tenantId()) || !batch.id().equals(attempt.batchId())) {
            throw new IllegalArgumentException("attempt tenant/batch mismatch");
        }
        if (attempt.items().size() != batch.items().size()) {
            throw new IllegalArgumentException("prepared item count mismatch");
        }
        if (batchRepository.markSigning(
                batch.tenantId(),
                batch.id(),
                attempt.estimatedGas(),
                attempt.gasLimit(),
                attempt.maxFeePerGas(),
                attempt.maxPriorityFeePerGas()) != 1) {
            throw new IllegalStateException("batch is not lock-owned for signing");
        }
        for (int index = 0; index < attempt.items().size(); index++) {
            PreparedItem item = attempt.items().get(index);
            if (item.itemIndex() != index) {
                throw new IllegalArgumentException("prepared item indexes must be contiguous");
            }
            if (batchItemRepository.markSigned(
                    batch.tenantId(),
                    batch.id(),
                    index,
                    item.authorityAddress(),
                    item.authorizationIncluded(),
                    item.authorizationNonce(),
                    item.operationNonce(),
                    Timestamp.from(item.signatureDeadline()),
                    item.callGasLimit()) != 1) {
                throw new IllegalStateException("prepared item does not match claimed item");
            }
        }
        batchAttemptRepository.insert(
                UUID.randomUUID(),
                batch.tenantId(),
                batch.id(),
                attempt.relayerNonce(),
                attempt.txHash(),
                attempt.maxFeePerGas(),
                attempt.maxPriorityFeePerGas(),
                attempt.gasLimit(),
                attempt.calldataHash(),
                attempt.signedTxCiphertext(),
                attempt.encryptionKeyVersion());
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markSubmitted(UUID tenantId, UUID batchId, String txHash) {
        if (batchRepository.markSubmitted(tenantId, batchId, txHash) != 1) {
            throw new IllegalStateException("batch submission transition failed");
        }
        batchAttemptRepository.markSubmitted(tenantId, batchId, txHash);
        batchItemRepository.markSubmitted(tenantId, batchId);
        for (Long collectionId : batchItemRepository.listCollectionRecordIds(tenantId, batchId)) {
            collectionRecordRepository.markSent(tenantId, collectionId, txHash);
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markBroadcastUnknown(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        String message = truncate(errorMessage, 1000);
        batchRepository.markBroadcastUnknown(tenantId, batchId, errorCode, message);
        batchAttemptRepository.markUnknown(tenantId, batchId, errorCode, message);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markRecoveryError(EvmBroadcastAttempt attempt, String errorCode, String errorMessage) {
        String message = truncate(errorMessage, 1000);
        batchAttemptRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), attempt.txHash(), errorCode, message);
        batchRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), errorCode, message);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void releaseUnbroadcastBatch(Batch batch, String errorCode, String errorMessage) {
        int attempts = batchAttemptRepository.countByBatch(batch.tenantId(), batch.id());
        if (attempts != 0) {
            throw new IllegalStateException("signed/outbox batch cannot be released as unbroadcast");
        }
        String message = truncate(errorMessage, 1000);
        if (batchRepository.markFailedIfUnbroadcast(batch.tenantId(), batch.id(), errorCode, message) != 1) {
            throw new IllegalStateException("unbroadcast batch failure transition failed");
        }
        for (var item : batchItemRepository.listForRelease(batch.tenantId(), batch.id())) {
            long collectionId = item.collectionRecordId();
            String status = batchItemRepository.countAttemptHistory(batch.tenantId(), collectionId) >= 3 ? "FAILED" : "RETRYABLE";
            if (batchItemRepository.markReleased(
                    batch.tenantId(),
                    batch.id(),
                    item.itemIndex(),
                    status,
                    errorCode) != 1) {
                throw new IllegalStateException("unbroadcast batch item transition failed");
            }
            collectionRecordRepository.updateExecution(
                    batch.tenantId(),
                    collectionId,
                    "FAILED".equals(status) ? "FAILED" : "RETRYING",
                    null,
                    message);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 查询指定租户链上的启用 Gas 热地址。
     */
    private CustodyAddressRecord findHotAddress(UUID tenantId, String chain, String address) {
        for (var gas : gasAccountRepository.listActiveByTenantAndChain(tenantId, chain)) {
            UUID custodyId = gas.custodyAddressId();
            var hot = custodyAddressRepository.findFullByTenantAndId(tenantId, custodyId).orElse(null);
            if (hot != null && "ACTIVE".equals(hot.status()) && address.equalsIgnoreCase(String.valueOf(hot.address()))) {
                return hot;
            }
        }
        return null;
    }

    private record CandidateGroup(UUID tenantId, String assetSymbol, String hotWallet, String tokenContract, int decimals) {
    }

    public record ClaimedItem(
            long collectionRecordId,
            String collectionNo,
            UUID tenantId,
            UUID custodyAddressId,
            String fromAddress,
            String toAddress,
            BigDecimal amount,
            BigInteger amountAtomic,
            ChainAddressRecord authorityChainAddress) {
    }

    public record Batch(
            UUID id,
            UUID tenantId,
            String chain,
            String network,
            String assetSymbol,
            String tokenContract,
            int tokenDecimals,
            String hotWallet,
            String batchHash,
            Instant signatureDeadline,
            Configuration config,
            ChainAddressRecord relayerChainAddress,
            List<ClaimedItem> items) {
    }

    public record PreparedItem(
            int itemIndex,
            String authorityAddress,
            boolean authorizationIncluded,
            BigInteger authorizationNonce,
            BigInteger operationNonce,
            Instant signatureDeadline,
            long callGasLimit) {
    }

    public record PreparedAttempt(
            UUID tenantId,
            UUID batchId,
            long estimatedGas,
            long gasLimit,
            BigInteger maxFeePerGas,
            BigInteger maxPriorityFeePerGas,
            BigInteger relayerNonce,
            String txHash,
            String calldataHash,
            String signedTxCiphertext,
            String encryptionKeyVersion,
            List<PreparedItem> items) {
    }

    public record PendingBatch(
            UUID tenantId,
            UUID batchId,
            String txHash,
            String status,
            int requiredConfirmations,
            String collectorAddress) {
    }

    @FunctionalInterface
    public interface SignedAttemptFactory {

        SignedAttempt create(BigInteger reservedNonce);
    }

    public record SignedAttempt(
            Evm7702BatchTransactionService.SignedBatchTransaction signedTransaction,
            PreparedAttempt attempt,
            BigDecimal reservedFee) {
    }
}
