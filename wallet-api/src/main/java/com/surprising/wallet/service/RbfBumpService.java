package com.surprising.wallet.service;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoSigningTask;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainFeeRateRepository;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.UtxoRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RbfBumpService {
    private final ChainSigningTransactionRepository signing;
    private final UtxoRepository utxos;
    private final CollectionRecordRepository collections;
    private final WithdrawalOrderRepository orders;
    private final WalletRuntimeConfigService runtimeConfig;
    private final QueueWorker worker;
    private final ChainFeeRateRepository feeRates;
    private final ObjectMapper json;

    /** Fee replacement, durable request receipt, next-stage enqueue and acknowledgement share the worker transaction. */
    public void process() {
        worker.drain(WalletQueue.RBF, 100, message -> {
            var body = JacksonJson.readObject(json, message.body());
            var task = bumpFee(body.path("transactionId").asInt(), QueueTenant.require(json, message),
                    body.path("expectedTxId").asText());
            if (task == null) return null;
            if (signing.recordEnqueued("BTC", task.transactionId(), task.request().requestId()) != 1) {
                throw new IllegalStateException("replacement signing request changed");
            }
            return new QueueWorker.Next(WalletQueue.SIGN_FIRST, JacksonJson.writeValue(json, task));
        });
    }

    /** expectedTxId makes duplicate, stale and terminal requests no-ops. */
    @Transactional(rollbackFor = Throwable.class)
    public UtxoSigningTask bumpFee(int transactionId, UUID tenantId, String expectedTxId) {
        if (expectedTxId == null || !expectedTxId.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("RBF expectedTxId is required");
        }
        var transaction = signing.lockById("BTC", transactionId)
                .orElseThrow(() -> new IllegalArgumentException("transaction missing"));
        var request = transaction.request();
        if (!request.tenantId().equals(tenantId)) throw new IllegalArgumentException("RBF tenant mismatch");
        if (transaction.status() != Constants.SENT || !expectedTxId.equals(transaction.txId())) return null;
        boolean collection = request.operation() == UtxoSigningRequest.Operation.COLLECTION;
        runtimeConfig.requireTaskEnabled("BTC", collection ? WalletRuntimeConfigService.TASK_COLLECTION
                : WalletRuntimeConfigService.TASK_WITHDRAW, "BTC fee replacement");
        if (transaction.signed() == null) throw new IllegalStateException("broadcast transaction missing");
        if (transaction.attempts().size() >= 5) throw new IllegalArgumentException("RBF attempt limit exceeded");
        long oldRate = request.feeRate();
        String configured = feeRates.find("btc");
        long nextRate = Math.max(configured == null ? 0 : Long.parseLong(configured),
                Math.max(Math.multiplyExact(oldRate, 2), Math.addExact(oldRate, 5)));
        if (oldRate <= 0 || nextRate > 1000) throw new IllegalArgumentException("RBF fee rate limit exceeded");
        request.validateForSigning();
        long input = request.inputAtomic();
        long nextFee = Math.multiplyExact(P2wshFeeCalculator.estimateVBytes(request.inputs().size(),
                collection ? 1 : request.payments().size() + 1), nextRate);
        if (nextFee > 100_000 || nextFee > input / 10 || nextFee <= transaction.signed().feeAtomic()) {
            throw new IllegalArgumentException("RBF absolute/relative fee limit exceeded");
        }
        for (var outpoint : request.inputs()) {
            if (!utxos.isLockedBy("BTC", outpoint.txId(), outpoint.vout(), Integer.toString(transactionId))) {
                throw new IllegalStateException("RBF original input lock missing");
            }
        }
        List<UtxoSigningRequest.Payment> payments = request.payments();
        if (collection) {
            if (payments.size() != 1 || !payments.getFirst().address().equals(request.changeAddress())) {
                throw new IllegalArgumentException("RBF collection output mismatch");
            }
            long output = Math.subtractExact(input, nextFee);
            if (output < Math.max(546, request.dustThreshold())) throw new IllegalArgumentException("RBF collection output dust");
            var previous = payments.getFirst();
            var amount = BigDecimal.valueOf(output).divide(request.asset().decimal());
            payments = List.of(new UtxoSigningRequest.Payment(previous.reference(), previous.userId(),
                    previous.address(), amount, previous.customerFee()));
            if (collections.updateAmounts(tenantId, "BTC", request.collectionNo(), amount,
                    BigDecimal.valueOf(nextFee).divide(request.asset().decimal())) != 1
                    || collections.updateStatus(tenantId, "BTC", request.collectionNo(), "SIGNING", null, null, null) != 1) {
                throw new IllegalStateException("RBF collection is terminal or missing");
            }
        } else {
            if (Math.subtractExact(Math.subtractExact(input, request.paymentAtomic()), nextFee) < 546) {
                throw new IllegalArgumentException("RBF insufficient change");
            }
            for (var payment : payments) {
                if (orders.updateStatus(tenantId, "BTC", payment.reference(), "SIGNING", null, null, null) != 1) {
                    throw new IllegalStateException("RBF withdrawal missing");
                }
            }
        }
        var replacement = request.replacement(UUID.randomUUID(), nextRate, payments);
        if (signing.update(transaction.replacement(replacement, Instant.now())) != 1) {
            throw new IllegalStateException("RBF signing update failed");
        }
        return new UtxoSigningTask(transactionId, replacement);
    }
}
