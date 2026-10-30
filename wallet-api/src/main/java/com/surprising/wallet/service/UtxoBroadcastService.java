package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.UtxoRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Date;

/** Apply a fenced signing result using only the database-owned business intent and attempt history. */
@Service
public class UtxoBroadcastService {
    private final ChainSigningTransactionRepository signing;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final UtxoRepository utxos;
    private final LedgerBalanceRepository balances;
    private final BlockchainRuntimeService runtime;
    private final PgmqClient queue;
    private final ObjectMapper json;
    private final String owner;

    public UtxoBroadcastService(ChainSigningTransactionRepository signing, WithdrawalOrderRepository orders,
                                CollectionRecordRepository collections, UtxoRepository utxos,
                                LedgerBalanceRepository balances, BlockchainRuntimeService runtime,
                                PgmqClient queue, ObjectMapper json, WalletTaskLeaseService leases) {
        this.signing = signing;
        this.orders = orders;
        this.collections = collections;
        this.utxos = utxos;
        this.balances = balances;
        this.runtime = runtime;
        this.queue = queue;
        this.json = json;
        this.owner = leases.ownerId() + "-broadcast";
    }

    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public void apply(UtxoSigningResult result) {
        var transaction = signing.lockById(result.chain(), result.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("signing transaction missing"));
        var request = transaction.request();
        if (!request.tenantId().equals(result.tenantId())) throw new IllegalArgumentException("signing tenant mismatch");
        if (transaction.terminal()) return;
        if (!request.requestId().equals(result.requestId())) throw new IllegalArgumentException("stale signing request");
        if (transaction.status() == Constants.SENT) return;
        if (!result.successful()) {
            fail(transaction, result.error());
            return;
        }
        if (!signing.claimBroadcast(result.chain(), transaction.id(), owner)) return;
        transaction = transaction.withSigned(result.transaction(), Constants.SIGNING);
        // Persist every signed attempt before RPC, including the initial attempt whose response may be lost.
        if (signing.update(transaction) != 1) throw new IllegalStateException("signing state changed before broadcast");
        String hash = runtime.broadcastSignedTransaction(request.asset().metadata(), result.transaction());
        if (hash == null || hash.isBlank()) {
            signing.markError(result.chain(), transaction.id(), "broadcast result unknown");
            updateBusiness(request, "BROADCAST_UNKNOWN", null, "broadcast result unknown");
            return;
        }
        if (!hash.equals(result.transaction().txId())) throw new IllegalStateException("broadcast transaction hash mismatch");
        if (signing.update(transaction.withSigned(result.transaction(), Constants.SENT)) != 1) {
            throw new IllegalStateException("signing state changed after broadcast");
        }
        updateBusiness(request, "SENT", hash, null);
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) return;
        for (var payment : request.payments()) {
            var order = orders.find(result.chain(), payment.reference(), request.tenantId()).orElseThrow();
            var event = WithdrawRecord.builder().withdrawId(payment.reference()).userId(payment.userId())
                    .address(payment.address()).balance(payment.amount()).fee(payment.customerFee())
                    .currency(request.asset().currencyId()).txId(hash).status((byte) Constants.SENT)
                    .createDate(order.getCreatedAt() == null ? null : Date.from(order.getCreatedAt()))
                    .updateDate(Date.from(Instant.now())).build();
            queue.send(WalletQueue.WITHDRAW_EVENT, JacksonJson.writeValue(json, event), QueueTenant.headers(request.tenantId()));
        }
    }

    private void fail(UtxoSigningTransaction transaction, String error) {
        var request = transaction.request();
        String chain = request.asset().chain();
        // Any signed attempt may still spend the inputs, even if its first broadcast timed out.
        if (transaction.signed() != null || !transaction.attempts().isEmpty()) {
            signing.markError(chain, transaction.id(), error);
            updateBusiness(request, "BROADCAST_UNKNOWN", null, error);
            return;
        }
        utxos.release(chain, Integer.toString(transaction.id()));
        if (signing.update(transaction.failed(error)) != 1) throw new IllegalStateException("signing failure state changed");
        if (request.operation() == UtxoSigningRequest.Operation.WITHDRAWAL) {
            for (var payment : request.payments()) {
                var order = orders.find(chain, payment.reference(), request.tenantId()).orElseThrow();
                String account = order.getDebitAccountId();
                if (account == null || account.isBlank()) throw new IllegalStateException("withdrawal debit account is missing; principal requires reconciliation");
                if (!balances.release(chain, chain, account, payment.amount().add(payment.customerFee()), request.tenantId())) {
                    throw new IllegalStateException("unable to release failed withdrawal balance");
                }
                if (orders.markPreBroadcastFailed(request.tenantId(), order.getId(), error) != 1) {
                    throw new IllegalStateException("unsigned withdrawal failure state changed");
                }
            }
        } else {
            updateBusiness(request, "FAILED", null, error);
        }
    }

    private void updateBusiness(UtxoSigningRequest request, String status, String hash, String error) {
        String chain = request.asset().chain();
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) {
            if (collections.updateStatus(request.tenantId(), chain, request.collectionNo(), status, hash, error, null) != 1) {
                throw new IllegalStateException("collection state changed");
            }
        } else {
            for (var payment : request.payments()) {
                if (orders.updateStatus(request.tenantId(), chain, payment.reference(), status, null, hash, error) != 1) {
                    throw new IllegalStateException("withdrawal state changed");
                }
            }
        }
    }
}
