package com.surprising.wallet.coordinator;

import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.service.CustodyGasAccountingService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.Evm7702ConfigurationService;
import com.surprising.wallet.repository.Evm7702ConfigRepository.Configuration;
import com.surprising.wallet.chain.model.EvmBroadcastAttempt;
import lombok.RequiredArgsConstructor;

/**
 * EIP-7702 提现批次事务：领取、签名事实、状态转换与资金结算共同提交或回滚。
 */
@Component
@RequiredArgsConstructor
public class Evm7702WithdrawalCoordinator {

    private final Evm7702ConfigRepository runtimeConfigRepository;
    private final ChainProfileRepository chainProfileRepository;
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CustodyWithdrawalRepository custodyWithdrawalRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final ChainAddressRepository chainAddressRepository;
    private final CustodyGasAccountRepository gasAccountRepository;
    private final CustodyAddressRepository custodyAddressRepository;
    private final EvmWithdrawalBatchRepository batchRepository;
    private final EvmWithdrawalBatchItemRepository batchItemRepository;
    private final EvmWithdrawalBatchAttemptRepository batchAttemptRepository;
    private final Evm7702PayoutAccountRepository payoutAccountRepository;

    private static final int MAX_ITEM_FAILURES = 3;
    private final CustodyGasAccountingService gasAccounting;
    private final CustodyGasUsageRepository gasUsages;
    private final LedgerBalanceRepository ledgerBalanceRepository;
    private final EvmNonceRepository nonces;
    private final WithdrawalSettlementService settlements;
    private final Evm7702ConfigurationService configurations;

    /**
     * 预留中继 nonce，保存已签名提现批次，并将逐笔网络费统一迁移到批次 Gas 预留。
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
            throw new IllegalStateException("signed payout attempt uses an unreserved relayer nonce");
        }
        saveSignedAttempt(batch, signedAttempt.attempt());
        for (ClaimedItem item : batch.items()) {
            gasUsages.find(
                    batch.tenantId(),
                    "WITHDRAWAL",
                    item.custodyWithdrawalId()).ifPresent(usage -> gasAccounting.release(
                    usage.tenantId(),
                    usage.operationType(),
                    usage.operationId(),
                    "network fee consolidated into EIP-7702 withdrawal batch " + batch.id()));
        }
        gasAccounting.reserve(batch.tenantId(), "WITHDRAWAL_BATCH", batch.id(), signedAttempt.reservedFee());
        return signedAttempt.signedTransaction();
    }

    /**
     * 按回执逐笔校验和结算提现；成功项入账，失败项按次数进入重试或最终失败状态。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void complete(
            PendingBatch batch,
            String txHash,
            BigInteger gasUsed,
            BigInteger effectiveGasPrice,
            BigInteger l2Fee,
            BigInteger l1Fee,
            BigInteger operatorFee,
            BigInteger blockNumber,
            String blockHash,
            List<Evm7702PayoutReceiptParser.ItemResult> results) {
        List<BatchItemIdentity> expected = listBatchItems(batch.tenantId(), batch.batchId());
        if (expected.size() != results.size()) {
            throw new IllegalStateException("payout receipt item count does not match batch");
        }
        int failures = 0;
        for (int index = 0; index < expected.size(); index++) {
            BatchItemIdentity item = expected.get(index);
            Evm7702PayoutReceiptParser.ItemResult result = results.get(index);
            if (item.itemIndex() != result.itemIndex() ||
                    !java.util.Arrays.equals(
                    item.withdrawalId(),
                    result.withdrawalId()) ||
                    !item.token().equalsIgnoreCase(result.token()) ||
                    !item.recipient().equalsIgnoreCase(result.recipient()) ||
                    !item.amountAtomic().equals(result.requestedAmount())) {
                throw new IllegalStateException("payout result identity does not match persisted withdrawal");
            }
            if (result.success()) {
                if (batchItemRepository.markResult(batch.tenantId(), batch.batchId(), index, result, "CONFIRMED") != 1) {
                    throw new IllegalStateException("payout item completion transition failed");
                }
                if (!settlements.confirm(batch.tenantId(), batch.chain(), item.orderNo(), txHash)) {
                    throw new IllegalStateException("withdrawal was already settled outside its payout batch");
                }
            } else {
                failures++;
                int attempts = batchItemRepository.countFailedAttempts(item.withdrawalOrderId()) + 1;
                String nextItemStatus = attempts >= MAX_ITEM_FAILURES ? "FAILED" : "RETRYABLE";
                if (batchItemRepository.markResult(batch.tenantId(), batch.batchId(), index, result, nextItemStatus) != 1) {
                    throw new IllegalStateException("failed payout item transition failed");
                }
                String error = "EIP-7702 payout item failed: " + result.errorHash() + " (attempt " + attempts + "/" + MAX_ITEM_FAILURES + ")";
                if (attempts >= MAX_ITEM_FAILURES) {
                    if (!ledgerBalanceRepository.release(
                            batch.chain(),
                            item.assetSymbol(),
                            item.debitAccountId(),
                            item.amount().add(item.fee()),
                            batch.tenantId())) {
                        throw new IllegalStateException("failed payout locked balance is inconsistent");
                    }
                    if (withdrawalOrderRepository.markFailed(item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000)) != 1) {
                        throw new IllegalStateException("unable to mark failed payout withdrawal");
                    }
                } else if (withdrawalOrderRepository.markRetrying(item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000)) != 1) {
                    throw new IllegalStateException("unable to retry failed payout withdrawal");
                }
            }
        }
        BigDecimal actualFee = actualFee(batch.chain(), l2Fee, l1Fee, operatorFee);
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        String status = failures == 0 ? "CONFIRMED" : failures == results.size() ? "FAILED" : "PARTIAL_FAILED";
        if (batchRepository.complete(
                batch.tenantId(),
                batch.batchId(),
                txHash,
                status,
                gasUsed,
                effectiveGasPrice,
                l2Fee,
                l1Fee,
                operatorFee,
                totalFee,
                actualFee,
                blockNumber,
                blockHash,
                null,
                null) != 1) {
            throw new IllegalStateException("payout batch completion transition failed");
        }
        batchAttemptRepository.markConfirmed(batch.tenantId(), batch.batchId(), txHash);
        Map<String, Object> row = batchRepository.find(batch.tenantId(), batch.batchId()).stream().findFirst().orElseThrow();
        payoutAccountRepository.markCompleted(
                batch.tenantId(),
                (String) row.get("chain"),
                Boolean.TRUE.equals(row.get("authorization_included")),
                numberOrNull(row.get("authorization_nonce")),
                numberOrNull(row.get("operation_nonce")).add(BigInteger.ONE),
                txHash,
                runtimeConfigRepository.find(
                        (String) row.get("chain"),
                        (String) row.get("network"),
                        null,
                        ((Number) row.get("delegate_version")).intValue()).getFirst().payoutDelegateAddress(),
                ((Number) row.get("delegate_version")).intValue());
        gasAccounting.settle(batch.tenantId(), "WITHDRAWAL_BATCH", batch.batchId(), actualFee, "EVM_RECEIPT", txHash);
    }

    /**
     * 处理整笔 EIP-7702 提现交易回滚，释放达到最终失败阈值的锁定余额并记录批次回执。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void completeReverted(
            PendingBatch batch,
            String txHash,
            BigInteger gasUsed,
            BigInteger effectiveGasPrice,
            BigInteger l2Fee,
            BigInteger l1Fee,
            BigInteger operatorFee,
            BigInteger blockNumber,
            String blockHash,
            String errorHash) {
        List<BatchItemIdentity> items = listBatchItems(batch.tenantId(), batch.batchId());
        for (BatchItemIdentity item : items) {
            int attempts = batchItemRepository.countFailedAttempts(item.withdrawalOrderId()) + 1;
            String nextStatus = attempts >= MAX_ITEM_FAILURES ? "FAILED" : "RETRYABLE";
            if (batchItemRepository.markReverted(batch.tenantId(), batch.batchId(), item.itemIndex(), nextStatus, errorHash) != 1) {
                throw new IllegalStateException("reverted payout item transition failed");
            }
            String error = "EIP-7702 payout transaction reverted: " + errorHash + " (attempt " + attempts + "/" + MAX_ITEM_FAILURES + ")";
            if (attempts >= MAX_ITEM_FAILURES) {
                if (!ledgerBalanceRepository.release(
                        batch.chain(),
                        item.assetSymbol(),
                        item.debitAccountId(),
                        item.amount().add(item.fee()),
                        batch.tenantId())) {
                    throw new IllegalStateException("reverted payout locked balance is inconsistent");
                }
                if (withdrawalOrderRepository.markFailed(item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000)) != 1) {
                    throw new IllegalStateException("unable to fail reverted payout withdrawal");
                }
            } else if (withdrawalOrderRepository.markRetrying(item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000)) != 1) {
                throw new IllegalStateException("unable to retry reverted payout withdrawal");
            }
        }
        BigDecimal actualFee = actualFee(batch.chain(), l2Fee, l1Fee, operatorFee);
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        if (batchRepository.complete(
                batch.tenantId(),
                batch.batchId(),
                txHash,
                "FAILED",
                gasUsed,
                effectiveGasPrice,
                l2Fee,
                l1Fee,
                operatorFee,
                totalFee,
                actualFee,
                blockNumber,
                blockHash,
                "OUTER_REVERTED",
                errorHash) != 1) {
            throw new IllegalStateException("reverted payout batch completion transition failed");
        }
        batchAttemptRepository.markFailed(batch.tenantId(), batch.batchId(), txHash, "OUTER_REVERTED", errorHash);
        Map<String, Object> row = batchRepository.find(batch.tenantId(), batch.batchId()).stream().findFirst().orElseThrow();
        String delegateAddress = runtimeConfigRepository.find(
                (String) row.get("chain"),
                (String) row.get("network"),
                null,
                ((Number) row.get("delegate_version")).intValue()).getFirst().payoutDelegateAddress();
        payoutAccountRepository.markReverted(
                batch.tenantId(),
                (String) row.get("chain"),
                Boolean.TRUE.equals(row.get("authorization_included")),
                numberOrNull(row.get("authorization_nonce")),
                numberOrNull(row.get("operation_nonce")),
                txHash,
                delegateAddress,
                ((Number) row.get("delegate_version")).intValue());
        gasAccounting.settle(batch.tenantId(), "WITHDRAWAL_BATCH", batch.batchId(), actualFee, "EVM_REVERTED_RECEIPT", txHash);
    }

    /**
     * 收敛签名和广播之前失败的批次，释放每笔提现锁定余额并触发失败状态对账。
     *
     * <p>该路径没有链上交易，也没有批次 Gas 预留；只有订单从未决状态第一次变为 FAILED
     * 时才释放余额，重复恢复同一批次不会重复释放资金。</p>
     */
    @Transactional(rollbackFor = Throwable.class)
    public void failUnbroadcast(UnbroadcastBatch batch) {
        List<BatchItemIdentity> items = listBatchItems(batch.tenantId(), batch.batchId());
        String error = batch.errorMessage() == null ? "EIP-7702 payout preparation failed" : batch.errorMessage();
        for (BatchItemIdentity item : items) {
            int transitioned = withdrawalOrderRepository.markPreBroadcastFailed(
                    item.tenantId(),
                    item.withdrawalOrderId(),
                    truncate(error, 1000));
            if (transitioned == 1) {
                if (!ledgerBalanceRepository.release(
                        batch.chain(),
                        item.assetSymbol(),
                        item.debitAccountId(),
                        item.amount().add(item.fee()),
                        item.tenantId())) {
                    throw new IllegalStateException("pre-broadcast payout locked balance is inconsistent");
                }
            } else if (!isTerminalWithdrawal(item)) {
                throw new IllegalStateException("pre-broadcast payout withdrawal failure transition lost");
            }
            int itemUpdated = batchItemRepository.markPreBroadcastFailed(
                    item.tenantId(),
                    item.batchId(),
                    item.itemIndex(),
                    "PREPARATION_FAILED");
            if (itemUpdated != 1 && !"FAILED".equalsIgnoreCase(item.status())) {
                throw new IllegalStateException("pre-broadcast payout item failure transition lost");
            }
        }
        if (batchRepository.markFailedIfUnbroadcast(batch.tenantId(), batch.batchId(), "PREPARATION_FAILED", truncate(error, 1000)) == 0) {
            throw new IllegalStateException("pre-broadcast payout batch failure transition lost");
        }
    }

    /**
     * 按链上原子费用和数据库中的原生资产精度计算提现批次账务费用。
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
        if (!config.batchWithdrawalEnabled()) {
            return Optional.empty();
        }
        List<Map<String, Object>> assets = chainAssetRepository.listActiveByChain(chain);
        List<TokenConfiguration> tokens = tokenConfigRepository.listEnabled(chain, network);
        List<Map<String, Object>> candidates = withdrawalOrderRepository.listClaimable(chain, 500);
        CandidateGroup group = null;
        for (Map<String, Object> order : candidates) {
            CandidateGroup candidate = mapCandidate(order, chain, assets, tokens);
            if (candidate == null) {
                continue;
            }
            Instant createdAt = ((Timestamp) order.get("created_at")).toInstant();
            boolean oldEnough = !createdAt.isAfter(Instant.now().minusMillis(config.withdrawalMaxWaitMs()));
            boolean sibling = candidates.stream().anyMatch(other -> !order.get("id").equals(other.get("id")) &&
                    java.util.Objects.equals(
                    order.get("tenant_id"),
                    other.get("tenant_id")) &&
                    String.valueOf(order.get("asset_symbol")).equalsIgnoreCase(String.valueOf(other.get("asset_symbol"))) &&
                    String.valueOf(order.get("from_address")).equalsIgnoreCase(String.valueOf(other.get("from_address"))));
            if (oldEnough || sibling) {
                group = candidate;
                break;
            }
        }
        if (group == null) {
            return Optional.empty();
        }
        List<ClaimedItem> items = new ArrayList<>();
        for (Map<String, Object> order : candidates) {
            if (!group.tenantId().equals(order.get("tenant_id")) ||
                    !group.assetSymbol().equalsIgnoreCase(String.valueOf(order.get("asset_symbol"))) ||
                    !group.hotWallet().equalsIgnoreCase(String.valueOf(order.get("from_address")))) {
                continue;
            }
            List<Map<String, Object>> custodyRows = custodyWithdrawalRepository.listByOrder(
                    group.tenantId(),
                    ((Number) order.get("id")).longValue());
            if (custodyRows.isEmpty()) {
                continue;
            }
            Map<String, Object> custody = custodyRows.get(0);
            BigDecimal amount = (BigDecimal) order.get("amount");
            BigInteger atomic;
            try {
                atomic = amount.movePointRight(group.decimals()).toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new IllegalStateException("withdrawal amount exceeds asset precision", e);
            }
            if (atomic.signum() <= 0) {
                throw new IllegalStateException("withdrawal amount must be positive");
            }
            UUID custodyWithdrawalId = (UUID) custody.get("id");
            items.add(new ClaimedItem(
                    ((Number) order.get("id")).longValue(),
                    custodyWithdrawalId,
                    (UUID) custody.get("custody_address_id"),
                    (String) order.get("order_no"),
                    (String) order.get("to_address"),
                    amount,
                    (BigDecimal) order.get("fee"),
                    atomic,
                    (String) order.get("debit_account_id"),
                    Numeric.hexStringToByteArray(withdrawalHash(group.tenantId(), custodyWithdrawalId))));
            if (items.size() >= config.withdrawalMaxBatchItems()) {
                break;
            }
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }
        UUID batchId = UUID.randomUUID();
        String batchHash = Numeric.toHexString(Hash.sha3((group.tenantId() + ":WITHDRAWAL:" + batchId).getBytes(StandardCharsets.UTF_8)));
        payoutAccountRepository.upsert(
                UUID.randomUUID(),
                group.tenantId(),
                chain,
                network,
                group.hotChainAddress().getId(),
                group.hotWallet());
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
        Instant deadline = Instant.now().plusSeconds(config.signatureTtlSeconds());
        for (int index = 0; index < items.size(); index++) {
            ClaimedItem item = items.get(index);
            if (batchItemRepository.insert(
                    UUID.randomUUID(),
                    group.tenantId(),
                    batchId,
                    index,
                    item.withdrawalOrderId(),
                    item.custodyWithdrawalId(),
                    Numeric.toHexString(item.withdrawalId()),
                    item.recipient(),
                    group.tokenContract(),
                    item.amountAtomic()) != 1) {
                throw new IllegalStateException("withdrawal batch item insert failed");
            }
            if (withdrawalOrderRepository.claimSigning(group.tenantId(), item.withdrawalOrderId()) != 1) {
                throw new IllegalStateException("withdrawal item claim lost");
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
                deadline,
                group.hotChainAddress(),
                config,
                resolved.relayer(),
                List.copyOf(items)));
    }

    private void saveSignedAttempt(Batch batch, PreparedAttempt attempt) {
        if (!batch.tenantId().equals(attempt.tenantId()) || !batch.id().equals(attempt.batchId())) {
            throw new IllegalArgumentException("payout attempt tenant/batch mismatch");
        }
        if (batchRepository.markSigning(
                batch.tenantId(),
                batch.id(),
                attempt.authorizationIncluded(),
                attempt.authorizationNonce(),
                attempt.operationNonce(),
                Timestamp.from(attempt.signatureDeadline()),
                attempt.estimatedGas(),
                attempt.gasLimit(),
                attempt.maxFeePerGas(),
                attempt.maxPriorityFeePerGas()) != 1) {
            throw new IllegalStateException("payout batch is not lock-owned for signing");
        }
        if (batchItemRepository.markSigned(batch.tenantId(), batch.id(), batch.items().size()) != batch.items().size()) {
            throw new IllegalStateException("not all payout items were prepared for signing");
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
            throw new IllegalStateException("payout submission transition failed");
        }
        batchAttemptRepository.markSubmitted(tenantId, batchId, txHash);
        batchItemRepository.markSubmitted(tenantId, batchId);
        for (Map<String, Object> item : batchItemRepository.listByBatch(tenantId, batchId)) {
            withdrawalOrderRepository.markSent(tenantId, ((Number) item.get("withdrawal_order_id")).longValue(), txHash);
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markBroadcastUnknown(UUID tenantId, UUID batchId, String code, String message) {
        String text = truncate(message, 1000);
        batchRepository.markBroadcastUnknown(tenantId, batchId, code, text);
        batchAttemptRepository.markUnknown(tenantId, batchId, code, text);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markRecoveryError(EvmBroadcastAttempt attempt, String code, String message) {
        String text = truncate(message, 1000);
        batchAttemptRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), attempt.txHash(), code, text);
        batchRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), code, text);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void releaseUnbroadcastBatch(Batch batch, String code, String message) {
        int attempts = batchAttemptRepository.countByBatch(batch.tenantId(), batch.id());
        if (attempts != 0) {
            throw new IllegalStateException("signed payout batch cannot be released");
        }
        String text = truncate(message, 1000);
        for (Map<String, Object> item : batchItemRepository.listCreated(batch.tenantId(), batch.id())) {
            if (withdrawalOrderRepository.markRetrying(
                    batch.tenantId(),
                    ((Number) item.get("withdrawal_order_id")).longValue(),
                    text) != 1) {
                throw new IllegalStateException("unbroadcast payout withdrawal retry transition failed");
            }
            batchItemRepository.markRetryable(batch.tenantId(), batch.id(), ((Number) item.get("item_index")).intValue(), code);
        }
        if (batchRepository.markFailedIfLocked(batch.tenantId(), batch.id(), code, text) != 1) {
            throw new IllegalStateException("unbroadcast payout batch failure transition failed");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private CandidateGroup mapCandidate(
            Map<String, Object> order,
            String chain,
            List<Map<String, Object>> assets,
            List<TokenConfiguration> tokens) {
        UUID tenantId = (UUID) order.get("tenant_id");
        String assetSymbol = String.valueOf(order.get("asset_symbol"));
        Map<String, Object> asset = assets.stream().filter(row -> assetSymbol.equalsIgnoreCase(String.valueOf(row.get("symbol")))).findFirst().orElse(null);
        if (asset == null) {
            return null;
        }
        boolean nativeAsset = Boolean.TRUE.equals(asset.get("native_asset"));
        TokenConfiguration token = tokens.stream().filter(row -> assetSymbol.equalsIgnoreCase(row.symbol())).findFirst().orElse(null);
        if (!nativeAsset && token == null) {
            return null;
        }
        ChainAddressRecord hot = chainAddressRepository.findEnabledByTenantAndAddress(
                tenantId,
                chain,
                String.valueOf(order.get("from_address"))).orElse(null);
        if (hot == null || !hasActiveGasAddress(tenantId, chain, String.valueOf(order.get("from_address")))) {
            return null;
        }
        return new CandidateGroup(
                tenantId,
                assetSymbol,
                String.valueOf(order.get("from_address")),
                nativeAsset ? "0x0000000000000000000000000000000000000000" : token.contractAddress(),
                ((Number) asset.get("decimals")).intValue(),
                hot);
    }

    /**
     * 判断租户链上提现来源是否为启用的 Gas 托管地址。
     */
    private boolean hasActiveGasAddress(UUID tenantId, String chain, String address) {
        for (Map<String, Object> gas : gasAccountRepository.listActiveByTenantAndChain(tenantId, chain)) {
            Map<String, Object> custody = custodyAddressRepository.findByTenantAndId(
                    tenantId,
                    (UUID) gas.get("custody_address_id")).orElse(null);
            if (custody != null &&
                    "ACTIVE".equals(custody.get("status")) &&
                    address.equalsIgnoreCase(String.valueOf(custody.get("address")))) {
                return true;
            }
        }
        return false;
    }

    private static String withdrawalHash(UUID tenantId, UUID custodyWithdrawalId) {
        return Numeric.toHexString(Hash.sha3((tenantId + ":WITHDRAWAL:" + custodyWithdrawalId).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 将数据库数值安全转换为大整数。
     */
    private static BigInteger numberOrNull(Object value) {
        return value == null ? null : ((BigDecimal) value).toBigIntegerExact();
    }

    private List<BatchItemIdentity> listBatchItems(UUID tenantId, UUID batchId) {
        return batchItemRepository.listByBatch(tenantId, batchId).stream().map(item -> {
            long orderId = ((Number) item.get("withdrawal_order_id")).longValue();
            Map<String, Object> order = withdrawalOrderRepository.findById(tenantId, orderId).orElseThrow();
            return new BatchItemIdentity(
                    tenantId,
                    batchId,
                    ((Number) item.get("item_index")).intValue(),
                    orderId,
                    (UUID) item.get("custody_withdrawal_id"),
                    (String) order.get("order_no"),
                    Numeric.hexStringToByteArray((String) item.get("withdrawal_id_hash")),
                    (String) item.get("token_contract"),
                    (String) item.get("recipient"),
                    ((BigDecimal) item.get("requested_amount_atomic")).toBigIntegerExact(),
                    (String) order.get("asset_symbol"),
                    (BigDecimal) order.get("amount"),
                    (BigDecimal) order.get("fee"),
                    (String) order.get("debit_account_id"),
                    (String) item.get("status"));
        }).toList();
    }

    /**
     * 判断提现订单是否已经进入不需要再次扣账的终态。
     */
    private boolean isTerminalWithdrawal(BatchItemIdentity item) {
        return withdrawalOrderRepository.findById(
                item.tenantId(),
                item.withdrawalOrderId()).map(row -> Set.of(
                "FAILED",
                "CONFIRMED").contains(String.valueOf(row.get("status")))).orElse(false);
    }

    private record CandidateGroup(
            UUID tenantId,
            String assetSymbol,
            String hotWallet,
            String tokenContract,
            int decimals,
            ChainAddressRecord hotChainAddress) {
    }

    public record ClaimedItem(
            long withdrawalOrderId,
            UUID custodyWithdrawalId,
            UUID custodyAddressId,
            String orderNo,
            String recipient,
            BigDecimal amount,
            BigDecimal fee,
            BigInteger amountAtomic,
            String debitAccountId,
            byte[] withdrawalId) {

        public ClaimedItem {
            withdrawalId = withdrawalId.clone();
        }

        /**
         * 处理 {@code withdrawalId} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
         */
        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }
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
            ChainAddressRecord hotChainAddress,
            Configuration config,
            ChainAddressRecord relayerChainAddress,
            List<ClaimedItem> items) {
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
            boolean authorizationIncluded,
            BigInteger authorizationNonce,
            BigInteger operationNonce,
            Instant signatureDeadline) {
    }

    public record PendingBatch(
            UUID tenantId,
            UUID batchId,
            String chain,
            String txHash,
            String status,
            String hotWallet,
            int requiredConfirmations) {
    }

    /**
     * 没有签名尝试和链上交易的预广播失败批次。
     */
    public record UnbroadcastBatch(UUID tenantId, UUID batchId, String chain, String network, String errorCode, String errorMessage) {
    }

    private record BatchItemIdentity(
            UUID tenantId,
            UUID batchId,
            int itemIndex,
            long withdrawalOrderId,
            UUID custodyWithdrawalId,
            String orderNo,
            byte[] withdrawalId,
            String token,
            String recipient,
            BigInteger amountAtomic,
            String assetSymbol,
            BigDecimal amount,
            BigDecimal fee,
            String debitAccountId,
            String status) {

        public BatchItemIdentity {
            withdrawalId = withdrawalId.clone();
        }

        /**
         * 处理 {@code withdrawalId} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
         */
        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }
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
