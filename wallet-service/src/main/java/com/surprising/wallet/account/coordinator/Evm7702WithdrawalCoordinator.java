package com.surprising.wallet.account.coordinator;

import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.service.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.service.chain.evm.Evm7702PayoutReceiptParser;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import com.surprising.wallet.account.repository.Evm7702WithdrawalRepository;

/** Atomically coordinates nonce/outbox, batch Gas and per-withdrawal ledger settlement. */
@Service
public class Evm7702WithdrawalCoordinator {
    private static final int MAX_ITEM_FAILURES = 3;    private final Evm7702WithdrawalRepository repository;    private final CustodyRepository custodyRepository;    private final ChainJdbcRepository chainRepository;

    public Evm7702WithdrawalCoordinator(Evm7702WithdrawalRepository repository,
                                        CustodyRepository custodyRepository,
                                        ChainJdbcRepository chainRepository) {
        this.repository = repository;
        this.custodyRepository = custodyRepository;
        this.chainRepository = chainRepository;
    }

    @Transactional(rollbackFor = Throwable.class)
    public Evm7702BatchTransactionService.SignedBatchTransaction persistSignedAttempt(
            Evm7702WithdrawalRepository.Batch batch, String relayerAddress,
            BigInteger rpcPendingNonce, SignedAttemptFactory factory) {
        BigInteger reservedNonce = chainRepository.reserveEvmNonce(
                batch.chain(), relayerAddress.toLowerCase(), rpcPendingNonce);
        SignedAttempt signedAttempt = factory.create(reservedNonce);
        if (!reservedNonce.equals(signedAttempt.attempt().relayerNonce())) {
            throw new IllegalStateException("signed payout attempt uses an unreserved relayer nonce");
        }
        repository.saveSignedAttempt(batch, signedAttempt.attempt());
        for (Evm7702WithdrawalRepository.ClaimedItem item : batch.items()) {
            custodyRepository.findGasUsage(item.custodyWithdrawalId()).ifPresent(usage ->
                    custodyRepository.releaseGasUsage(
                            usage.tenantId(), usage.operationType(), usage.operationId(),
                            "network fee consolidated into EIP-7702 withdrawal batch " + batch.id()));
        }
        custodyRepository.reserveGasUsage(
                batch.tenantId(), "WITHDRAWAL_BATCH", batch.id(),
                batch.id().toString(), batch.chain(), signedAttempt.reservedFee());
        return signedAttempt.signedTransaction();
    }

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
                if (!chainRepository.confirmWithdrawalAndSettle(
                        batch.tenantId(), batch.chain(), item.orderNo(), txHash,
                        item.assetSymbol(), item.debitAccountId(), item.amount().add(item.fee()))) {
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
                    if (!chainRepository.releaseLockedBalance(
                            batch.tenantId(), batch.chain(), item.assetSymbol(),
                            item.debitAccountId(), item.amount().add(item.fee()))) {
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
        repository.completeBatchMetadata(
                batch, txHash, gasUsed, effectiveGasPrice, l2Fee, l1Fee, operatorFee,
                blockNumber, blockHash, failures, results.size(), state.operationNonce(),
                payoutDelegateAddress);
        BigDecimal actualFee = new BigDecimal(l2Fee.add(l1Fee).add(operatorFee))
                .movePointLeft(18).stripTrailingZeros();
        custodyRepository.settleGasUsage(
                batch.tenantId(), "WITHDRAWAL_BATCH", batch.batchId(),
                actualFee, "EVM_RECEIPT", txHash);
    }

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
                if (!chainRepository.releaseLockedBalance(
                        batch.tenantId(), batch.chain(), item.assetSymbol(),
                        item.debitAccountId(), item.amount().add(item.fee()))) {
                    throw new IllegalStateException("reverted payout locked balance is inconsistent");
                }
                if (repository.markWithdrawalFailed(item, error) != 1) {
                    throw new IllegalStateException("unable to fail reverted payout withdrawal");
                }
            } else if (repository.markWithdrawalRetrying(item, error) != 1) {
                throw new IllegalStateException("unable to retry reverted payout withdrawal");
            }
        }
        repository.completeRevertedBatchMetadata(
                batch, txHash, gasUsed, effectiveGasPrice, l2Fee, l1Fee,
                operatorFee, blockNumber, blockHash, errorHash);
        BigDecimal actualFee = new BigDecimal(l2Fee.add(l1Fee).add(operatorFee))
                .movePointLeft(18).stripTrailingZeros();
        custodyRepository.settleGasUsage(
                batch.tenantId(), "WITHDRAWAL_BATCH", batch.batchId(),
                actualFee, "EVM_REVERTED_RECEIPT", txHash);
    }

    @FunctionalInterface
    public interface SignedAttemptFactory {
        SignedAttempt create(BigInteger reservedNonce);
    }

    public record SignedAttempt(Evm7702BatchTransactionService.SignedBatchTransaction signedTransaction,
                                Evm7702WithdrawalRepository.PreparedAttempt attempt,
                                BigDecimal reservedFee) { }
}
