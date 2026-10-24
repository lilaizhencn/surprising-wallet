package com.surprising.wallet.chain;

import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.UtxoRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.WithdrawalSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/** Confirm the attempt that actually mined, including older attempts during replacement signing or broadcast. */
@Service
@RequiredArgsConstructor
public class BitcoinLikeSettlementService {
    private final ChainSigningTransactionRepository signing;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final UtxoRepository utxos;
    private final WithdrawalSettlementService settlements;

    @Transactional(rollbackFor = Throwable.class)
    public void settleConfirmed(String chain, int transactionId, String txId) {
        var transaction = signing.lockById(chain, transactionId).orElseThrow();
        if (transaction.terminal()) return;
        var confirmed = transaction.confirmed(txId, Instant.now());
        var request = confirmed.request();
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) {
            if (request.payments().size() != 1) throw new IllegalStateException("collection output missing");
            if (collections.updateAmounts(request.tenantId(), chain, request.collectionNo(),
                    request.payments().getFirst().amount(), BigDecimal.valueOf(confirmed.signed().feeAtomic())
                            .divide(request.asset().decimal())) != 1
                    || collections.markConfirmed(request.tenantId(), chain, request.collectionNo(), txId) != 1) {
                throw new IllegalStateException("collection settlement state changed");
            }
        } else {
            for (var payment : request.payments()) {
                markOrderConfirming(request, payment, txId);
                settlements.confirm(request.tenantId(), chain, payment.reference(), txId);
            }
        }
        if (request.inputs().isEmpty()
                || utxos.markSpent(chain, Integer.toString(transactionId), txId) != request.inputs().size()) {
            throw new IllegalStateException("confirmed transaction input lock mismatch");
        }
        if (signing.update(confirmed) != 1) throw new IllegalStateException("signing confirmation state changed");
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markConfirming(String chain, int transactionId, String txId) {
        var transaction = signing.lockById(chain, transactionId).orElseThrow();
        if (transaction.terminal()) return;
        var request = transaction.attempt(txId).request();
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) {
            if (collections.updateStatus(request.tenantId(), chain, request.collectionNo(), "CONFIRMING", txId, null, null) != 1) {
                throw new IllegalStateException("collection confirmation state changed");
            }
        } else {
            for (var payment : request.payments()) markOrderConfirming(request, payment, txId);
        }
    }

    private void markOrderConfirming(UtxoSigningRequest request, UtxoSigningRequest.Payment payment, String txId) {
        String orderNo = payment.reference();
        var order = orders.lockForSettlement(request.tenantId(), request.asset().chain(), orderNo).orElseThrow();
        if (!request.asset().chain().equals(order.getAssetSymbol()) || order.getUserId() != payment.userId()
                || !payment.address().equals(order.getToAddress())
                || payment.amount().compareTo(order.getAmount()) != 0
                || payment.customerFee().compareTo(order.getFee()) != 0) {
            throw new IllegalStateException("withdrawal does not match the mined signing request");
        }
        if ("CONFIRMED".equals(order.getStatus())) {
            if (!txId.equals(order.getTxHash())) throw new IllegalStateException("confirmed withdrawal hash mismatch");
        } else if (orders.markUtxoAttemptConfirming(request.tenantId(), request.asset().chain(), orderNo, txId) != 1) {
            throw new IllegalStateException("withdrawal is not confirmable");
        }
    }
}
