package com.surprising.wallet.chain.model;

import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.utils.Constants;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** API-owned signing lifecycle; only the immutable request crosses the signer boundary. */
public record UtxoSigningTransaction(int id, UtxoSigningRequest request, UtxoSignedTransaction signed,
                                     List<Attempt> attempts, short status, UUID enqueuedRequestId,
                                     String error, Instant createdAt, Instant updatedAt) {
    public UtxoSigningTransaction {
        Objects.requireNonNull(request);
        attempts = List.copyOf(attempts);
    }

    public boolean terminal() { return status == Constants.CONFIRM || status == Constants.DELETE; }
    public String txId() { return signed == null ? null : signed.txId(); }

    public List<String> transactionIds() {
        var ids = new LinkedHashSet<String>();
        if (signed != null) ids.add(signed.txId());
        for (var attempt : attempts) ids.add(attempt.transaction().txId());
        return List.copyOf(ids);
    }

    public UtxoSigningTransaction withSigned(UtxoSignedTransaction value, short nextStatus) {
        return new UtxoSigningTransaction(id, request, Objects.requireNonNull(value), attempts,
                nextStatus, enqueuedRequestId, null, createdAt, updatedAt);
    }

    public UtxoSigningTransaction withStatus(short nextStatus) {
        return new UtxoSigningTransaction(id, request, signed, attempts,
                nextStatus, enqueuedRequestId, error, createdAt, updatedAt);
    }

    public UtxoSigningTransaction failed(String reason) {
        return new UtxoSigningTransaction(id, request, signed, attempts,
                Constants.DELETE, enqueuedRequestId, reason, createdAt, updatedAt);
    }

    public UtxoSigningTransaction replacement(UtxoSigningRequest nextRequest, Instant now) {
        if (!request.tenantId().equals(nextRequest.tenantId()) || !request.asset().equals(nextRequest.asset())
                || request.operation() != nextRequest.operation()
                || !Objects.equals(request.collectionNo(), nextRequest.collectionNo())) {
            throw new IllegalArgumentException("replacement business identity mismatch");
        }
        var history = new ArrayList<>(attempts);
        history.add(new Attempt(request, Objects.requireNonNull(signed, "replacement requires signed transaction"), now));
        return new UtxoSigningTransaction(id, nextRequest, null, history, Constants.SIGNING,
                enqueuedRequestId, null, createdAt, now);
    }

    public Attempt attempt(String txId) {
        if (signed != null && signed.txId().equals(txId)) return new Attempt(request, signed, null);
        return attempts.stream().filter(value -> value.transaction().txId().equals(txId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown transaction attempt"));
    }

    public UtxoSigningTransaction confirmed(String txId, Instant now) {
        var confirmed = attempt(txId);
        var history = new ArrayList<>(attempts);
        if (signed != null && !signed.txId().equals(txId)) history.add(new Attempt(request, signed, now));
        return new UtxoSigningTransaction(id, confirmed.request(), confirmed.transaction(), history,
                Constants.CONFIRM, enqueuedRequestId, null, createdAt, now);
    }

    public record Attempt(UtxoSigningRequest request, UtxoSignedTransaction transaction, Instant replacedAt) {
        public Attempt {
            Objects.requireNonNull(request);
            Objects.requireNonNull(transaction);
        }
    }
}
