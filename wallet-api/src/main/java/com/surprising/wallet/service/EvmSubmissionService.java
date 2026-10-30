package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.repository.*;
import java.math.BigInteger;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commit nonce, signed transaction and owned business hash before contacting a broadcast RPC. */
@Service
@RequiredArgsConstructor
public class EvmSubmissionService {
    private final WithdrawalOrderRepository withdrawals;
    private final CollectionRecordRepository collections;
    private final EvmTransactionRepository transactions;
    private final EvmNonceRepository nonces;
    private final CustodyAssetRecoveryRepository recoveries;

    public enum Kind { WITHDRAWAL, COLLECTION, RECOVERY }

    public record Operation(UUID tenantId, String reference, Kind kind) {
        public Operation {
            java.util.Objects.requireNonNull(tenantId, "EVM operation tenant is required");
            java.util.Objects.requireNonNull(kind, "EVM operation kind is required");
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("EVM operation reference is required");
        }
    }

    public EvmTransactionRecord existing(Operation operation, String chain) {
        String hash = switch (operation.kind()) {
            case WITHDRAWAL -> withdrawals.findTxHash(chain,operation.reference(),operation.tenantId()).orElse(null);
            case COLLECTION -> collections.findTxHash(operation.tenantId(),chain,operation.reference()).orElse(null);
            case RECOVERY -> recoveries.require(operation.tenantId(),UUID.fromString(operation.reference())).recoveryTxHash();
        };
        return hash == null ? null : requireSigned(chain,hash);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public EvmTransactionRecord prepare(Operation operation, String chain, String sender, BigInteger chainNonce,
                                         SignedTransactionFactory signer) throws Exception {
        String status;
        String from;
        String hash;
        if (operation.kind() == Kind.RECOVERY) {
            var owner = recoveries.lockForSubmission(operation.tenantId(),UUID.fromString(operation.reference())).orElseThrow();
            if (!chain.equals(owner.actualChain())) throw new IllegalArgumentException("recovery chain differs from operation");
            status="EXECUTING".equals(owner.status()) ? "SIGNING" : owner.status();
            from=owner.destinationAddress(); hash=owner.recoveryTxHash();
        } else if (operation.kind() == Kind.COLLECTION) {
            var owner = collections.lockForSubmission(operation.tenantId(),chain,operation.reference()).orElseThrow();
            status=owner.getStatus(); from=owner.getFromAddress(); hash=owner.getTxHash();
        } else {
            var owner = withdrawals.lockForSettlement(operation.tenantId(),chain,operation.reference()).orElseThrow();
            status=owner.getStatus(); from=owner.getFromAddress(); hash=owner.getTxHash();
        }
        if (!sender.equalsIgnoreCase(from)) throw new IllegalStateException("EVM sender differs from owned operation");
        if (hash != null) return requireSigned(chain,hash);
        if (!"SIGNING".equals(status)) throw new IllegalStateException("EVM operation is not signing");
        var signed = signer.sign(nonces.reserve(chain,sender.toLowerCase(java.util.Locale.ROOT),chainNonce));
        if (!chain.equals(signed.getChain()) || !sender.equalsIgnoreCase(signed.getFromAddress())
                || signed.getRawPayload()==null || !org.web3j.crypto.Hash.sha3(signed.getRawPayload()).equalsIgnoreCase(signed.getTxHash())) {
            throw new IllegalStateException("signed EVM submission identity is invalid");
        }
        transactions.upsert(signed);
        int bound = switch (operation.kind()) {
            case WITHDRAWAL -> withdrawals.bindPrepared(operation.tenantId(),chain,operation.reference(),signed.getTxHash());
            case COLLECTION -> collections.bindPrepared(operation.tenantId(),chain,operation.reference(),signed.getTxHash());
            case RECOVERY -> recoveries.bindPrepared(operation.tenantId(),UUID.fromString(operation.reference()),signed.getTxHash());
        };
        if (bound != 1) throw new IllegalStateException("EVM operation changed before signed bytes were committed");
        return signed;
    }

    @FunctionalInterface
    public interface SignedTransactionFactory {
        EvmTransactionRecord sign(BigInteger nonce) throws Exception;
    }

    private EvmTransactionRecord requireSigned(String chain,String hash) {
        var signed = transactions.findSigned(chain,hash).orElseThrow(() -> new IllegalStateException("owned EVM signed transaction is missing"));
        if (!org.web3j.crypto.Hash.sha3(signed.getRawPayload()).equalsIgnoreCase(hash)) throw new IllegalStateException("stored EVM raw transaction hash mismatch");
        return signed;
    }
}
