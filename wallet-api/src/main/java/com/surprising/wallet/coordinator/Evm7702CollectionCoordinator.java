package com.surprising.wallet.coordinator;

import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702ReceiptParser;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import com.surprising.wallet.repository.Evm7702CollectionRepository;

/**
 * 协调 EIP-7702 归集批次的事务边界。
 *
 * <p>负责原子完成中继 nonce 预留、签名出站记录、Gas 预留和回执后的费用结算，
 * 保证链上批次与租户账务记录保持一致。</p>
 */
@Service
public class Evm7702CollectionCoordinator {
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702CollectionRepository repository;
    /**
     * 保存 {@code custodyRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyRepository custodyRepository;
    /**
     * 保存 {@code chainRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository chainRepository;
    /**
     * 构造 {@code Evm7702CollectionCoordinator}，初始化该组件运行所需的状态和依赖。
     */
    public Evm7702CollectionCoordinator(Evm7702CollectionRepository repository,
                                        CustodyRepository custodyRepository,
                                        ChainJdbcRepository chainRepository) {
        this.repository = repository;
        this.custodyRepository = custodyRepository;
        this.chainRepository = chainRepository;
    }

    /**
     * 预留中继 nonce，持久化已签名归集交易，并为该批次预留租户 Gas。
     */
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

    /**
     * 校验归集回执并完成批次、原生费用和租户 Gas 使用量的最终结算。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void complete(String chain, Evm7702CollectionRepository.PendingBatch batch,
                         String txHash, BigInteger gasUsed, BigInteger effectiveGasPrice,
                         BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee,
                         BigInteger blockNumber, String blockHash,
                         List<Evm7702ReceiptParser.ItemResult> results) {
        BigDecimal actualFee = actualFee(chain, l2Fee, l1Fee, operatorFee);
        repository.completeBatch(
                batch.tenantId(), batch.batchId(), txHash, gasUsed, effectiveGasPrice,
                l2Fee, l1Fee, operatorFee, actualFee,
                blockNumber, blockHash, results);
        custodyRepository.settleGasUsage(
                batch.tenantId(), "COLLECTION_BATCH", batch.batchId(),
                actualFee, "EVM_RECEIPT", txHash);
    }

    /**
     * 按链上原子费用和数据库中的原生资产精度计算账务费用。
     */
    private BigDecimal actualFee(
            String chain, BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee) {
        var profile = chainRepository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for " + chain));
        var asset = chainRepository.findAsset(chain, profile.getNativeSymbol())
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
     * 创建带有已预留 nonce 的签名归集尝试。
     */
    @FunctionalInterface
    public interface SignedAttemptFactory {
        /**
         * 使用已预留 nonce 创建签名交易及其持久化信息。
         */
        SignedAttempt create(BigInteger reservedNonce);
    }

    public record SignedAttempt(
            Evm7702BatchTransactionService.SignedBatchTransaction signedTransaction,
            Evm7702CollectionRepository.PreparedAttempt attempt,
            BigDecimal reservedFee) {
    }
}
