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
import com.surprising.wallet.common.signing.UtxoSigningTask;
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
    private final UtxoMpcSigningService mpc;
    private final BlockchainRuntimeService runtime;
    private final PgmqClient queue;
    private final ObjectMapper json;
    private final String owner;

    public UtxoBroadcastService(ChainSigningTransactionRepository signing, WithdrawalOrderRepository orders,
                                CollectionRecordRepository collections,
                                UtxoMpcSigningService mpc, BlockchainRuntimeService runtime,
                                PgmqClient queue, ObjectMapper json, WalletTaskLeaseService leases) {
        this.signing = signing;
        this.orders = orders;
        this.collections = collections;
        this.mpc = mpc;
        this.runtime = runtime;
        this.queue = queue;
        this.json = json;
        this.owner = leases.ownerId() + "-broadcast";
    }

    public record Prepared(UtxoSigningResult result, UtxoSigningRequest request) { }
    public record Observation(Prepared prepared, String hash, String error) { }

    /** Commit selected signed bytes and every owned business hash before exposing them to RPC. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public Prepared prepare(UtxoSigningResult result, PgmqClient.Message claim) {
        if (!queue.lock(WalletQueue.SIGN_DONE, claim)) return null;
        var transaction = requireOwned(result);
        if (transaction.terminal() || transaction.status() == Constants.SENT) return null;
        if (!transaction.request().requestId().equals(result.requestId())) throw new IllegalArgumentException("stale signing request");
        // No error message can prove that an admitted threshold request has no signature.
        // Rebuild only from the immutable request and the selected durable input signatures.
        mpc.verifyResult(new UtxoSigningTask(transaction.id(), transaction.request()), result);
        if (transaction.signed() != null && !transaction.signed().equals(result.transaction())) {
            throw new IllegalArgumentException("UTXO result differs from committed signed bytes");
        }
        // Distinct duplicate messages must not share a lease. A retry of this same
        // durable message may resume immediately; completion is still read_ct-fenced.
        String claimOwner = owner + ":" + claim.id();
        if (!signing.claimBroadcast(result.chain(), transaction.id(), claimOwner)) {
            throw new IllegalStateException("UTXO broadcast lease is held; retain the current queue message");
        }
        transaction = transaction.withSigned(result.transaction(), Constants.SIGNING);
        if (signing.recordPrepared(transaction, claimOwner) != 1) throw new IllegalStateException("UTXO raw receipt changed");
        bindBusinessHash(transaction);
        return new Prepared(result, transaction.request());
    }

    public Observation broadcast(Prepared prepared) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("UTXO broadcast requires committed raw and business hashes");
        }
        if (prepared == null) return null;
        String hash;
        try { hash = runtime.broadcastSignedTransaction(prepared.request().asset().metadata(), prepared.result().transaction()); }
        catch (RuntimeException uncertain) { return new Observation(prepared, null, uncertain.getClass().getSimpleName()); }
        if (hash == null || hash.isBlank()) return new Observation(prepared, null, "broadcast result unknown");
        if (!hash.equals(prepared.result().transaction().txId())) return new Observation(prepared, null, "broadcast transaction hash mismatch");
        return new Observation(prepared, hash, null);
    }

    /** Runs inside QueueWorker's current read_ct transaction with events and archive/retry. */
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public boolean complete(Observation observation) {
        if (observation == null) return true;
        var result = observation.prepared().result();
        var transaction = requireOwned(result);
        if (transaction.terminal() || transaction.status() == Constants.SENT) return true;
        if (!transaction.request().requestId().equals(result.requestId())) throw new IllegalArgumentException("stale signing observation");
        if (!result.transaction().equals(transaction.signed())) throw new IllegalStateException("observed UTXO bytes differ from receipt");
        if (observation.hash() != null && !observation.hash().equals(transaction.txId())) {
            throw new IllegalArgumentException("observed UTXO hash differs from committed bytes");
        }
        var request = transaction.request();
        if (observation.hash() == null) {
            signing.markError(result.chain(), transaction.id(), observation.error());
            updateBusiness(request, "BROADCAST_UNKNOWN", null, observation.error());
            return false;
        }
        if (signing.update(transaction.withSigned(result.transaction(), Constants.SENT)) != 1) {
            throw new IllegalStateException("signing state changed after broadcast");
        }
        updateBusiness(request, "SENT", observation.hash(), null);
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) return true;
        for (var payment : request.payments()) {
            var order = orders.find(result.chain(), payment.reference(), request.tenantId()).orElseThrow();
            if ("CONFIRMING".equals(order.getStatus())) continue;
            var event = WithdrawRecord.builder().withdrawId(payment.reference()).userId(payment.userId())
                    .address(payment.address()).balance(payment.amount()).fee(payment.customerFee())
                    .currency(request.asset().currencyId()).txId(observation.hash()).status((byte) Constants.SENT)
                    .createDate(order.getCreatedAt() == null ? null : Date.from(order.getCreatedAt()))
                    .updateDate(Date.from(Instant.now())).build();
            queue.send(WalletQueue.WITHDRAW_EVENT, JacksonJson.writeValue(json, event), QueueTenant.headers(request.tenantId()));
        }
        return true;
    }

    private UtxoSigningTransaction requireOwned(UtxoSigningResult result) {
        var transaction = signing.lockById(result.chain(), result.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("signing transaction missing"));
        if (!transaction.request().tenantId().equals(result.tenantId())) throw new IllegalArgumentException("signing tenant mismatch");
        return transaction;
    }

    private void bindBusinessHash(UtxoSigningTransaction transaction) {
        var request = transaction.request();
        String chain = request.asset().chain(), hash = transaction.txId();
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) {
            var collection = collections.lockForSubmission(request.tenantId(), chain, request.collectionNo()).orElseThrow();
            var payment = request.payments().getFirst();
            if (request.payments().size() != 1 || !collection.getToAddress().equals(payment.address())
                    || collection.getAmount().compareTo(payment.amount()) != 0 || !chain.equals(collection.getAssetSymbol())) {
                throw new IllegalStateException("UTXO collection differs from signed intent");
            }
            requireBindable(collection.getStatus(), collection.getTxHash(), transaction);
            if (collections.bindUtxoPreparedHash(request.tenantId(), chain, request.collectionNo(), collection.getTxHash(), hash) != 1) {
                throw new IllegalStateException("collection changed before raw commit");
            }
        } else {
            for (var payment : request.payments().stream().sorted(java.util.Comparator.comparing(UtxoSigningRequest.Payment::reference)).toList()) {
                var order = orders.lockForSettlement(request.tenantId(), chain, payment.reference()).orElseThrow();
                if (!order.getToAddress().equals(payment.address()) || order.getAmount().compareTo(payment.amount()) != 0
                        || order.getFee().compareTo(payment.customerFee()) != 0 || !chain.equals(order.getAssetSymbol())
                        || order.getUserId() != payment.userId()) throw new IllegalStateException("UTXO payment differs from owned withdrawal");
                requireBindable(order.getStatus(), order.getTxHash(), transaction);
                if (orders.bindUtxoPreparedHash(request.tenantId(), chain, payment.reference(), order.getTxHash(), hash) != 1) {
                    throw new IllegalStateException("withdrawal changed before raw commit");
                }
            }
        }
    }

    private static void requireBindable(String state, String hash, UtxoSigningTransaction transaction) {
        if (!("SIGNING".equals(state) || "BROADCAST_UNKNOWN".equals(state)
                || ("CONFIRMING".equals(state) && transaction.txId().equals(hash)))
                || (hash != null && !transaction.transactionIds().contains(hash))) {
            throw new IllegalStateException("UTXO business owner or previous attempt changed");
        }
    }

    private void updateBusiness(UtxoSigningRequest request, String status, String hash, String error) {
        String chain = request.asset().chain();
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) {
            var current = collections.lockForSubmission(request.tenantId(), chain, request.collectionNo()).orElseThrow();
            if ("CONFIRMING".equals(current.getStatus())) return;
            if (collections.updateStatus(request.tenantId(), chain, request.collectionNo(), status, hash, error, null) != 1) {
                throw new IllegalStateException("collection state changed");
            }
        } else {
            for (var payment : request.payments()) {
                var current = orders.lockForSettlement(request.tenantId(), chain, payment.reference()).orElseThrow();
                if ("CONFIRMING".equals(current.getStatus())) continue;
                if (orders.updateStatus(request.tenantId(), chain, payment.reference(), status, null, hash, error) != 1) {
                    throw new IllegalStateException("withdrawal state changed");
                }
            }
        }
    }
}
