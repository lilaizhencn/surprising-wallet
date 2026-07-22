package com.surprising.wallet.jobs.account;

import com.surprising.wallet.jobs.custody.CustodyRepository;
import com.surprising.wallet.service.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.service.chain.evm.Evm7702ReceiptParser;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/** Keeps the signed outbox, tenant gas reservation and final settlement atomic. */
@Service
public class Evm7702CollectionCoordinator {
    private final Evm7702CollectionRepository repository;
    private final CustodyRepository custodyRepository;
    private final ChainJdbcRepository chainRepository;

    public Evm7702CollectionCoordinator(Evm7702CollectionRepository repository,
                                        CustodyRepository custodyRepository,
                                        ChainJdbcRepository chainRepository) {
        this.repository = repository;
        this.custodyRepository = custodyRepository;
        this.chainRepository = chainRepository;
    }

    @Transactional(rollbackFor = Throwable.class)
    public Evm7702BatchTransactionService.SignedBatchTransaction persistSignedAttempt(
            Evm7702CollectionRepository.Batch batch, String relayerAddress,
            BigInteger rpcPendingNonce, SignedAttemptFactory factory) {
        BigInteger reservedNonce = chainRepository.reserveEvmNonce(
                batch.chain(), relayerAddress.toLowerCase(), rpcPendingNonce);
        SignedAttempt signedAttempt = factory.create(reservedNonce);
        if (!reservedNonce.equals(signedAttempt.attempt().relayerNonce())) {
            throw new IllegalStateException("signed EIP-7702 attempt uses an unreserved relayer nonce");
        }
        repository.saveSignedAttempt(batch, signedAttempt.attempt());
        custodyRepository.reserveGasUsage(
                batch.tenantId(), "COLLECTION_BATCH", batch.id(),
                batch.id().toString(), batch.chain(), signedAttempt.reservedFee());
        return signedAttempt.signedTransaction();
    }

    @Transactional(rollbackFor = Throwable.class)
    public void complete(Evm7702CollectionRepository.PendingBatch batch,
                         String txHash, BigInteger gasUsed, BigInteger effectiveGasPrice,
                         BigInteger l1Fee, BigInteger operatorFee,
                         BigInteger blockNumber, String blockHash,
                         List<Evm7702ReceiptParser.ItemResult> results) {
        repository.completeBatch(
                batch.tenantId(), batch.batchId(), txHash, gasUsed, effectiveGasPrice,
                l1Fee, operatorFee,
                blockNumber, blockHash, results);
        BigDecimal actualFee = new BigDecimal(gasUsed.multiply(effectiveGasPrice)
                        .add(l1Fee).add(operatorFee))
                .movePointLeft(18).stripTrailingZeros();
        custodyRepository.settleGasUsage(
                batch.tenantId(), "COLLECTION_BATCH", batch.batchId(),
                actualFee, "EVM_RECEIPT", txHash);
    }

    @FunctionalInterface
    public interface SignedAttemptFactory {
        SignedAttempt create(BigInteger reservedNonce);
    }

    public record SignedAttempt(
            Evm7702BatchTransactionService.SignedBatchTransaction signedTransaction,
            Evm7702CollectionRepository.PreparedAttempt attempt,
            BigDecimal reservedFee) {
    }
}
