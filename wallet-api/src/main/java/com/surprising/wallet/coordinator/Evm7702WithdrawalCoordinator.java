package com.surprising.wallet.coordinator;

import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CustodyGasUsageRepository;
import com.surprising.wallet.service.CustodyGasAccountingService;
import com.surprising.wallet.repository.Evm7702WithdrawalRepository;
import com.surprising.wallet.repository.EvmNonceRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.service.WithdrawalSettlementService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协调 EIP-7702 批量提现的事务边界。
 *
 * <p>负责 nonce 预留、Gas 预留释放、逐笔提现状态推进、失败重试和最终账务结算，
 * 防止回执结果与提现锁定余额发生不一致。</p>
 */
@Component
public class Evm7702WithdrawalCoordinator {
    /**
     * 定义 {@code MAX_ITEM_FAILURES} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int MAX_ITEM_FAILURES = 3;
    private final Evm7702WithdrawalRepository repository;
    private final CustodyGasAccountingService gasAccounting;
    private final CustodyGasUsageRepository gasUsages;
    private final LedgerBalanceRepository ledgerBalanceRepository;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final EvmNonceRepository nonces;
    private final WithdrawalSettlementService settlements;

    /**
     * 构造 {@code Evm7702WithdrawalCoordinator}，初始化该组件运行所需的状态和依赖。
     */
    public Evm7702WithdrawalCoordinator(
            Evm7702WithdrawalRepository repository,
            CustodyGasAccountingService gasAccounting,
            LedgerBalanceRepository ledgerBalanceRepository,
            ChainProfileRepository chainProfileRepository,
            ChainAssetRepository chainAssetRepository,
            WithdrawalSettlementService settlements,
            EvmNonceRepository nonces, CustodyGasUsageRepository gasUsages) {
        this.repository = repository;
        this.gasAccounting = gasAccounting;
        this.gasUsages = gasUsages;
        this.ledgerBalanceRepository = ledgerBalanceRepository;
        this.chainProfileRepository = chainProfileRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.nonces = nonces;
        this.settlements = settlements;
    }

    /**
     * 预留中继 nonce，保存已签名提现批次，并将逐笔网络费统一迁移到批次 Gas 预留。
     */
    @Transactional(rollbackFor = Throwable.class)
    public Evm7702BatchTransactionService.SignedBatchTransaction persistSignedAttempt(
            Evm7702WithdrawalRepository.Batch batch, String relayerAddress,
            BigInteger rpcPendingNonce, SignedAttemptFactory factory) {
        BigInteger reservedNonce = nonces.reserve(
                batch.chain(), relayerAddress.toLowerCase(), rpcPendingNonce);
        SignedAttempt signedAttempt = factory.create(reservedNonce);
        if (!reservedNonce.equals(signedAttempt.attempt().relayerNonce())) {
            throw new IllegalStateException("signed payout attempt uses an unreserved relayer nonce");
        }
        repository.saveSignedAttempt(batch, signedAttempt.attempt());
        for (Evm7702WithdrawalRepository.ClaimedItem item : batch.items()) {
            gasUsages.find(batch.tenantId(), "WITHDRAWAL", item.custodyWithdrawalId()).ifPresent(usage ->
                    gasAccounting.release(
                            usage.tenantId(), usage.operationType(), usage.operationId(),
                            "network fee consolidated into EIP-7702 withdrawal batch " + batch.id()));
        }
        gasAccounting.reserve(
                batch.tenantId(), "WITHDRAWAL_BATCH", batch.id(),
                signedAttempt.reservedFee());
        return signedAttempt.signedTransaction();
    }

    /**
     * 按回执逐笔校验和结算提现；成功项入账，失败项按次数进入重试或最终失败状态。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void complete(Evm7702WithdrawalRepository.PendingBatch batch,
                         String txHash, BigInteger gasUsed, BigInteger effectiveGasPrice,
                         BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee,
                         BigInteger blockNumber, String blockHash,
                         List<Evm7702PayoutReceiptParser.ItemResult> results,
                         String payoutDelegateAddress) {
        List<Evm7702WithdrawalRepository.BatchItemIdentity> expected =
                repository.listBatchItems(batch.tenantId(), batch.batchId());
        if (expected.size() != results.size()) {
            throw new IllegalStateException("payout receipt item count does not match batch");
        }
        int failures = 0;
        for (int index = 0; index < expected.size(); index++) {
            Evm7702WithdrawalRepository.BatchItemIdentity item = expected.get(index);
            Evm7702PayoutReceiptParser.ItemResult result = results.get(index);
            if (item.itemIndex() != result.itemIndex()
                    || !java.util.Arrays.equals(item.withdrawalId(), result.withdrawalId())
                    || !item.token().equalsIgnoreCase(result.token())
                    || !item.recipient().equalsIgnoreCase(result.recipient())
                    || !item.amountAtomic().equals(result.requestedAmount())) {
                throw new IllegalStateException("payout result identity does not match persisted withdrawal");
            }
            if (result.success()) {
                if (repository.markItemResult(
                        batch.tenantId(), batch.batchId(), index, result, "CONFIRMED") != 1) {
                    throw new IllegalStateException("payout item completion transition failed");
                }
                if (!settlements.confirm(batch.tenantId(), batch.chain(), item.orderNo(), txHash)) {
                    throw new IllegalStateException("withdrawal was already settled outside its payout batch");
                }
            } else {
                failures++;
                int attempts = repository.countFailedAttempts(item.withdrawalOrderId()) + 1;
                String nextItemStatus = attempts >= MAX_ITEM_FAILURES ? "FAILED" : "RETRYABLE";
                if (repository.markItemResult(
                        batch.tenantId(), batch.batchId(), index, result, nextItemStatus) != 1) {
                    throw new IllegalStateException("failed payout item transition failed");
                }
                String error = "EIP-7702 payout item failed: " + result.errorHash()
                        + " (attempt " + attempts + "/" + MAX_ITEM_FAILURES + ")";
                if (attempts >= MAX_ITEM_FAILURES) {
                    if (!ledgerBalanceRepository.release(
                            batch.chain(),
                            item.assetSymbol(),
                            item.debitAccountId(),
                            item.amount().add(item.fee()),
                            batch.tenantId())) {
                        throw new IllegalStateException("failed payout locked balance is inconsistent");
                    }
                    if (repository.markWithdrawalFailed(item, error) != 1) {
                        throw new IllegalStateException("unable to mark failed payout withdrawal");
                    }
                } else if (repository.markWithdrawalRetrying(item, error) != 1) {
                    throw new IllegalStateException("unable to retry failed payout withdrawal");
                }
            }
        }
        Evm7702WithdrawalRepository.BatchState state =
                repository.requireBatchState(batch.tenantId(), batch.batchId());
        BigDecimal actualFee = actualFee(batch.chain(), l2Fee, l1Fee, operatorFee);
        repository.completeBatchMetadata(
                batch, txHash, gasUsed, effectiveGasPrice, l2Fee, l1Fee, operatorFee,
                actualFee,
                blockNumber, blockHash, failures, results.size(), state.operationNonce(),
                payoutDelegateAddress);
        gasAccounting.settle(
                batch.tenantId(), "WITHDRAWAL_BATCH", batch.batchId(),
                actualFee, "EVM_RECEIPT", txHash);
    }

    /**
     * 处理整笔 EIP-7702 提现交易回滚，释放达到最终失败阈值的锁定余额并记录批次回执。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void completeReverted(Evm7702WithdrawalRepository.PendingBatch batch,
                                 String txHash, BigInteger gasUsed,
                                 BigInteger effectiveGasPrice, BigInteger l2Fee,
                                 BigInteger l1Fee, BigInteger operatorFee,
                                 BigInteger blockNumber, String blockHash,
                                 String errorHash) {
        List<Evm7702WithdrawalRepository.BatchItemIdentity> items =
                repository.listBatchItems(batch.tenantId(), batch.batchId());
        for (Evm7702WithdrawalRepository.BatchItemIdentity item : items) {
            int attempts = repository.countFailedAttempts(item.withdrawalOrderId()) + 1;
            String nextStatus = attempts >= MAX_ITEM_FAILURES ? "FAILED" : "RETRYABLE";
            if (repository.markRevertedItem(
                    batch.tenantId(), batch.batchId(), item.itemIndex(), nextStatus, errorHash) != 1) {
                throw new IllegalStateException("reverted payout item transition failed");
            }
            String error = "EIP-7702 payout transaction reverted: " + errorHash
                    + " (attempt " + attempts + "/" + MAX_ITEM_FAILURES + ")";
            if (attempts >= MAX_ITEM_FAILURES) {
                if (!ledgerBalanceRepository.release(
                        batch.chain(),
                        item.assetSymbol(),
                        item.debitAccountId(),
                        item.amount().add(item.fee()),
                        batch.tenantId())) {
                    throw new IllegalStateException("reverted payout locked balance is inconsistent");
                }
                if (repository.markWithdrawalFailed(item, error) != 1) {
                    throw new IllegalStateException("unable to fail reverted payout withdrawal");
                }
            } else if (repository.markWithdrawalRetrying(item, error) != 1) {
                throw new IllegalStateException("unable to retry reverted payout withdrawal");
            }
        }
        BigDecimal actualFee = actualFee(batch.chain(), l2Fee, l1Fee, operatorFee);
        repository.completeRevertedBatchMetadata(
                batch, txHash, gasUsed, effectiveGasPrice, l2Fee, l1Fee,
                operatorFee, actualFee, blockNumber, blockHash, errorHash);
        gasAccounting.settle(
                batch.tenantId(), "WITHDRAWAL_BATCH", batch.batchId(),
                actualFee, "EVM_REVERTED_RECEIPT", txHash);
    }

    /**
     * 收敛签名和广播之前失败的批次，释放每笔提现锁定余额并触发失败状态对账。
     *
     * <p>该路径没有链上交易，也没有批次 Gas 预留；只有订单从未决状态第一次变为 FAILED
     * 时才释放余额，重复恢复同一批次不会重复释放资金。</p>
     */
    @Transactional(rollbackFor = Throwable.class)
    public void failUnbroadcast(Evm7702WithdrawalRepository.UnbroadcastBatch batch) {
        List<Evm7702WithdrawalRepository.BatchItemIdentity> items =
                repository.listBatchItems(batch.tenantId(), batch.batchId());
        String error = batch.errorMessage() == null ? "EIP-7702 payout preparation failed" : batch.errorMessage();
        for (Evm7702WithdrawalRepository.BatchItemIdentity item : items) {
            int transitioned = repository.markUnbroadcastWithdrawalFailed(item, error);
            if (transitioned == 1) {
                if (!ledgerBalanceRepository.release(
                        batch.chain(),
                        item.assetSymbol(),
                        item.debitAccountId(),
                        item.amount().add(item.fee()),
                        item.tenantId())) {
                    throw new IllegalStateException("pre-broadcast payout locked balance is inconsistent");
                }
            } else if (!repository.isUnbroadcastWithdrawalFailed(item)
                    && !repository.isTerminalWithdrawal(item)) {
                throw new IllegalStateException("pre-broadcast payout withdrawal failure transition lost");
            }
            int itemUpdated = repository.markUnbroadcastItemFailed(
                    item, "PREPARATION_FAILED");
            if (itemUpdated != 1 && !"FAILED".equalsIgnoreCase(item.status())) {
                throw new IllegalStateException("pre-broadcast payout item failure transition lost");
            }
        }
        if (repository.markUnbroadcastBatchFailed(batch, "PREPARATION_FAILED", error) == 0) {
            throw new IllegalStateException("pre-broadcast payout batch failure transition lost");
        }
    }

    /**
     * 按链上原子费用和数据库中的原生资产精度计算提现批次账务费用。
     */
    private BigDecimal actualFee(
            String chain, BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee) {
        var profile = chainProfileRepository.findAccountByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for " + chain));
        var asset = chainAssetRepository.findActiveDefinition(chain, profile.getNativeSymbol())
                .orElseThrow(() -> new IllegalStateException(
                        "missing active native chain_asset for " + chain));
        if (!Boolean.TRUE.equals(asset.getNativeAsset()) || asset.getDecimals() == null) {
            throw new IllegalStateException("invalid native chain_asset for " + chain);
        }
        return EvmFeeSupport.atomicToNative(
                l2Fee.add(l1Fee).add(operatorFee), asset.getDecimals(),
                java.math.RoundingMode.UP);
    }

    /**
     * 创建带有已预留 nonce 的签名提现尝试。
     */
    @FunctionalInterface
    public interface SignedAttemptFactory {
        /**
         * 使用已预留 nonce 创建签名交易及其持久化信息。
         */
        SignedAttempt create(BigInteger reservedNonce);
    }

    public record SignedAttempt(Evm7702BatchTransactionService.SignedBatchTransaction signedTransaction,
                                Evm7702WithdrawalRepository.PreparedAttempt attempt,
                                BigDecimal reservedFee) { }
}
