package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Cardano 链交易服务，使用 QuickTx 构造并签名交易，通过 Blockfrost 提交。
 *
 * <p>支持 ADA 原生币和 Cardano Native Asset 代币的发送、提现确认和归集。
 * 使用 bloxbean cardano-client 的 QuickTx API 简化交易构造流程。</p>
 *
 * @see CardanoBackendClient
 * @see CardanoKeyService
 */
@Service
@RequiredArgsConstructor
public
class CardanoTransactionService {

    /** 链标识 */
    private static final String CHAIN = CardanoBackendClient.CHAIN;

    /** 原生币符号 */
    private static final String SYMBOL = "ADA";

    /** ADA 的小数位数 */
    private static final int ADA_DECIMALS = 6;

    /** Blockfrost 后端客户端 */
    private final CardanoBackendClient backendClient;

    /** Cardano 密钥服务 */
    private final CardanoKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public String sendNative(ChainAddressRecord from, String toAddress, BigInteger lovelace) {
        return send(from, toAddress, Amount.lovelace(lovelace));
    }
    /**
     * 发送或广播 {@code sendToken} 对应的链上请求，并返回节点处理结果。
     */
    public String sendToken(ChainAddressRecord from, TokenDefinition token, String toAddress, BigDecimal amount) {
        BigInteger atomic = toAtomic(amount, token.getDecimals());
        String unit = CardanoAssetUnit.fromTokenContract(token.getContractAddress());
        return send(from, toAddress, Amount.asset(unit, atomic));
    }

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String txHash,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        if (!confirmed(profile, txHash)) {
            return false;
        }
        return repository.confirmWithdrawalAndSettle(tenantId, CHAIN, orderNo, txHash,
                assetSymbol, debitAccountId, debitAmount);
    }

    /**
     * 处理 {@code collectNative} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger lovelace) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "cardano collectNative");
        return collect(tenantId, collectionNo, () -> sendNative(from, hotAddress, lovelace));
    }

    /**
     * 处理 {@code collectToken} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectToken(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "cardano collectToken");
        return collect(tenantId, collectionNo, () -> sendToken(from, token, hotAddress, amount));
    }

    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        if (confirmed(profile, txHash)) {
            return repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
        }
        return false;
    }
    /**
     * 编码 {@code toLovelace} 对应的数据，生成链上或接口所需的表示。
     */
    public static BigInteger toLovelace(BigDecimal amount) {
        return toAtomic(amount, ADA_DECIMALS);
    }
    /**
     * 解析 {@code fromLovelace} 对应的输入，并转换为当前业务模型。
     */
    public static BigDecimal fromLovelace(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount).movePointLeft(ADA_DECIMALS)
                .stripTrailingZeros();
    }
    /**
     * 发送或广播 {@code send} 对应的链上请求，并返回节点处理结果。
     */
    private String send(ChainAddressRecord from, String toAddress, Amount amount) {
        return backendClient.withBackend((backend, node, profile) -> {
            SecretKey secretKey = secretKey(from);
            Tx tx = new Tx()
                    .from(from.getAddress())
                    .payToAddress(toAddress, amount)
                    .withChangeAddress(from.getAddress());
            TxResult result = new QuickTxBuilder(backend)
                    .compose(tx)
                    .feePayer(from.getAddress())
                    .withSigner(SignerProviders.signerFrom(secretKey))
                    .complete();
            if (result == null || !result.isSuccessful()) {
                throw new IllegalStateException("Cardano transaction submit failed: "
                        + (result == null ? "<empty>" : result.getResponse()));
            }
            String hash = result.getTxHash();
            if (hash == null || hash.isBlank()) {
                hash = result.getValue();
            }
            if (hash == null || hash.isBlank()) {
                throw new IllegalStateException("Cardano transaction submit returned empty tx hash");
            }
            return hash;
        });
    }
    /**
     * 处理 {@code collect} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private String collect(java.util.UUID tenantId, String collectionNo, TxSubmitter submitter) {
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Cardano collection is not retryable"));
        }
        try {
            String txHash = submitter.submit();
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    /**
     * 处理 {@code confirmed} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private boolean confirmed(AccountChainProfile profile, String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return false;
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            boolean confirmed = backendClient.withBackend((backend, node, activeProfile) -> {
                var txResult = backend.getTransactionService().getTransaction(txHash);
                if (CardanoBackendClient.isNotFound(txResult)) {
                    return false;
                }
                var tx = CardanoBackendClient.requireSuccess(txResult, "transaction content");
                var latest = CardanoBackendClient.requireSuccess(
                        backend.getBlockService().getLatestBlock(), "latest block");
                long confirmations = Math.max(0L, latest.getHeight() - tx.getBlockHeight() + 1L);
                return confirmations >= Math.max(1, profile.getWithdrawConfirmations());
            });
            if (confirmed) {
                return true;
            }
            sleep(1_000L);
        }
        return false;
    }
    /**
     * 构建或生成 {@code derivedKey} 对应的结果，并执行输入和状态校验。
     */
    private Ed25519DerivedKey derivedKey(ChainAddressRecord from) {
        return keyService.derive(from.getUserId(), from.getBiz(), from.getAddressIndex());
    }
    /**
     * 转换或计算 {@code secretKey} 对应的值，统一金额、格式和边界规则。
     */
    private SecretKey secretKey(ChainAddressRecord from) {
        try {
            return SecretKey.create(derivedKey(from).privateSeed());
        } catch (CborSerializationException e) {
            throw new IllegalStateException("unable to create Cardano signing key", e);
        }
    }
    /**
     * 编码 {@code toAtomic} 对应的数据，生成链上或接口所需的表示。
     */
    private static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    /**
     * 校验 {@code requireTaskEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    /**
     * 转换或计算 {@code sleep} 对应的值，统一金额、格式和边界规则。
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cardano wait interrupted", e);
        }
    }

    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    @FunctionalInterface
    private interface TxSubmitter {
        /**
         * 发送或广播 {@code submit} 对应的链上请求，并返回节点处理结果。
         */
        String submit();
    }
}
