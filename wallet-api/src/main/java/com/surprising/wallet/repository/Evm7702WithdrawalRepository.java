package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 负责钱包业务数据的查询、持久化、租户隔离和事务边界管理。
 */
@Component
public class Evm7702WithdrawalRepository {
    /**
     * 保存 {@code jdbc}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbc;
    /**
     * 保存 {@code configRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702CollectionRepository configRepository;
    /** EIP-7702 配置单表仓储。 */
    private final Evm7702ConfigRepository runtimeConfigRepository;
    /** 链配置单表仓储。 */
    private final ChainProfileRepository chainProfileRepository;
    /** 提现订单单表仓储。 */
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    /** 托管提现单表仓储。 */
    private final CustodyWithdrawalRepository custodyWithdrawalRepository;
    /** 链资产单表仓储。 */
    private final ChainAssetRepository chainAssetRepository;
    /** 代币配置单表仓储。 */
    private final TokenConfigRepository tokenConfigRepository;
    /** 链地址单表仓储。 */
    private final ChainAddressRepository chainAddressRepository;
    /** Gas 账户单表仓储。 */
    private final CustodyGasAccountRepository gasAccountRepository;
    /** 托管地址单表仓储。 */
    private final CustodyAddressRepository custodyAddressRepository;
    /** 提现批次单表仓储。 */
    private final EvmWithdrawalBatchRepository batchRepository;
    /** 提现批次项单表仓储。 */
    private final EvmWithdrawalBatchItemRepository batchItemRepository;
    /** 提现批次尝试单表仓储。 */
    private final EvmWithdrawalBatchAttemptRepository batchAttemptRepository;
    /** EIP-7702 提现账户单表仓储。 */
    private final Evm7702PayoutAccountRepository payoutAccountRepository;
    /**
     * 构造 {@code Evm7702WithdrawalRepository}，初始化该组件运行所需的状态和依赖。
     */
    public Evm7702WithdrawalRepository(JdbcTemplate jdbc,
                                       Evm7702CollectionRepository configRepository) {
        this.jdbc = jdbc;
        this.configRepository = configRepository;
        this.runtimeConfigRepository = new Evm7702ConfigRepository(jdbc);
        this.chainProfileRepository = new ChainProfileRepository(jdbc);
        this.withdrawalOrderRepository = new WithdrawalOrderRepository(jdbc);
        this.custodyWithdrawalRepository = new CustodyWithdrawalRepository(jdbc);
        this.chainAssetRepository = new ChainAssetRepository(jdbc);
        this.tokenConfigRepository = new TokenConfigRepository(jdbc);
        this.chainAddressRepository = new ChainAddressRepository(jdbc);
        this.gasAccountRepository = new CustodyGasAccountRepository(jdbc);
        this.custodyAddressRepository = new CustodyAddressRepository(jdbc);
        this.batchRepository = new EvmWithdrawalBatchRepository(jdbc);
        this.batchItemRepository = new EvmWithdrawalBatchItemRepository(jdbc);
        this.batchAttemptRepository = new EvmWithdrawalBatchAttemptRepository(jdbc);
        this.payoutAccountRepository = new Evm7702PayoutAccountRepository(jdbc);
    }

    /**
     * 校验 {@code requireRuntimeConfigVersion} 对应的前置条件，不满足时抛出明确异常。
     */
    public Evm7702CollectionRepository.RuntimeConfig requireRuntimeConfigVersion(
            String chain, String network, int version) {
        return configRepository.requireRuntimeConfigVersion(chain, network, version);
    }
    /**
     * 获取或查询 {@code listRuntimeTargets} 对应的数据，供调用方读取当前状态。
     */
    public List<RuntimeTarget> listRuntimeTargets() {
        List<RuntimeTarget> targets = new ArrayList<>();
        for (Map<String, Object> profile : chainProfileRepository.listAll()) {
            if (!Boolean.TRUE.equals(profile.get("enabled"))
                    || !"evm".equalsIgnoreCase(String.valueOf(profile.get("family")))) continue;
            String chain = String.valueOf(profile.get("chain"));
            String network = String.valueOf(profile.get("network"));
            List<Map<String, Object>> configs = runtimeConfigRepository.find(chain, network, null, null);
            boolean active = configs.stream().anyMatch(row -> "ACTIVE".equals(row.get("status"))
                    && Boolean.TRUE.equals(row.get("batch_withdrawal_enabled")));
            boolean managed = configs.stream().anyMatch(row -> Set.of("ACTIVE", "PAUSED").contains(row.get("status"))
                    && Boolean.TRUE.equals(row.get("batch_withdrawal_enabled")));
            boolean pending = batchRepository.listPending(chain, network, 1).size() > 0
                    || !batchRepository.listBroadcastUnknown(chain, network).isEmpty();
            if (managed || pending) targets.add(new RuntimeTarget(chain, network, active));
        }
        return targets.stream().sorted(java.util.Comparator.comparing(RuntimeTarget::chain)
                .thenComparing(RuntimeTarget::network)).toList();
    }

    /**
     * 执行 {@code claimNextBatch} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public Optional<Batch> claimNextBatch(String chain, String network) {
        Evm7702CollectionRepository.RuntimeConfig config =
                configRepository.requireActiveConfig(chain, network);
        if (!config.batchWithdrawalEnabled()) return Optional.empty();
        List<Map<String, Object>> assets = chainAssetRepository.listActiveByChain(chain);
        List<Map<String, Object>> tokens = tokenConfigRepository.listAll();
        List<Map<String, Object>> candidates = withdrawalOrderRepository.listClaimable(chain, 500);
        CandidateGroup group = null;
        for (Map<String, Object> order : candidates) {
            CandidateGroup candidate = mapCandidate(order, chain, network, config, assets, tokens);
            if (candidate == null) continue;
            Instant createdAt = ((Timestamp) order.get("created_at")).toInstant();
            boolean oldEnough = !createdAt.isAfter(Instant.now().minusMillis(config.withdrawalMaxWaitMs()));
            boolean sibling = candidates.stream().anyMatch(other -> !order.get("id").equals(other.get("id"))
                    && java.util.Objects.equals(order.get("tenant_id"), other.get("tenant_id"))
                    && String.valueOf(order.get("asset_symbol")).equalsIgnoreCase(String.valueOf(other.get("asset_symbol")))
                    && String.valueOf(order.get("from_address")).equalsIgnoreCase(String.valueOf(other.get("from_address"))));
            if (oldEnough || sibling) {
                group = candidate;
                break;
            }
        }
        if (group == null) return Optional.empty();

        List<ClaimedItem> items = new ArrayList<>();
        for (Map<String, Object> order : candidates) {
            if (!group.tenantId().equals(order.get("tenant_id"))
                    || !group.assetSymbol().equalsIgnoreCase(String.valueOf(order.get("asset_symbol")))
                    || !group.hotWallet().equalsIgnoreCase(String.valueOf(order.get("from_address")))) continue;
            List<Map<String, Object>> custodyRows = custodyWithdrawalRepository.listByOrder(group.tenantId(),
                    ((Number) order.get("id")).longValue());
            if (custodyRows.isEmpty()) continue;
            Map<String, Object> custody = custodyRows.get(0);
            BigDecimal amount = (BigDecimal) order.get("amount");
            BigInteger atomic;
            try {
                atomic = amount.movePointRight(group.decimals()).toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new IllegalStateException("withdrawal amount exceeds asset precision", e);
            }
            if (atomic.signum() <= 0) throw new IllegalStateException("withdrawal amount must be positive");
            UUID custodyWithdrawalId = (UUID) custody.get("id");
            items.add(new ClaimedItem(((Number) order.get("id")).longValue(), custodyWithdrawalId,
                    (UUID) custody.get("custody_address_id"), (String) order.get("order_no"),
                    (String) order.get("to_address"), amount, (BigDecimal) order.get("fee"), atomic,
                    (String) order.get("debit_account_id"), Numeric.hexStringToByteArray(
                            withdrawalHash(group.tenantId(), custodyWithdrawalId))));
            if (items.size() >= config.withdrawalMaxBatchItems()) break;
        }
        if (items.isEmpty()) return Optional.empty();

        UUID batchId = UUID.randomUUID();
        String batchHash = Numeric.toHexString(Hash.sha3(
                (group.tenantId() + ":WITHDRAWAL:" + batchId).getBytes(StandardCharsets.UTF_8)));
        payoutAccountRepository.upsert(UUID.randomUUID(), group.tenantId(), chain, network,
                group.hotChainAddress().getId(), group.hotWallet());
        batchRepository.insert(batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(), config.relayerAddress(),
                config.version(), batchHash, items.size());
        Instant deadline = Instant.now().plusSeconds(config.signatureTtlSeconds());
        for (int index = 0; index < items.size(); index++) {
            ClaimedItem item = items.get(index);
            if (batchItemRepository.insert(UUID.randomUUID(), group.tenantId(), batchId, index,
                    item.withdrawalOrderId(), item.custodyWithdrawalId(), Numeric.toHexString(item.withdrawalId()),
                    item.recipient(), group.tokenContract(), item.amountAtomic()) != 1) {
                throw new IllegalStateException("withdrawal batch item insert failed");
            }
            if (withdrawalOrderRepository.claimSigning(group.tenantId(), item.withdrawalOrderId()) != 1) {
                throw new IllegalStateException("withdrawal item claim lost");
            }
        }
        return Optional.of(new Batch(
                batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(), batchHash,
                deadline, group.hotChainAddress(), config, List.copyOf(items)));
    }

    /**
     * 记录或保存 {@code saveSignedAttempt} 对应的数据，并遵守幂等和事务约束。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void saveSignedAttempt(Batch batch, PreparedAttempt attempt) {
        if (!batch.tenantId().equals(attempt.tenantId()) || !batch.id().equals(attempt.batchId())) {
            throw new IllegalArgumentException("payout attempt tenant/batch mismatch");
        }
        if (batchRepository.markSigning(batch.tenantId(), batch.id(), attempt.authorizationIncluded(),
                attempt.authorizationNonce(), attempt.operationNonce(), Timestamp.from(attempt.signatureDeadline()),
                attempt.estimatedGas(), attempt.gasLimit(), attempt.maxFeePerGas(),
                attempt.maxPriorityFeePerGas()) != 1) {
            throw new IllegalStateException("payout batch is not lock-owned for signing");
        }
        if (batchItemRepository.markSigned(batch.tenantId(), batch.id(), batch.items().size())
                != batch.items().size()) {
            throw new IllegalStateException("not all payout items were prepared for signing");
        }
        batchAttemptRepository.insert(UUID.randomUUID(), batch.tenantId(), batch.id(), attempt.relayerNonce(),
                attempt.txHash(), attempt.maxFeePerGas(), attempt.maxPriorityFeePerGas(), attempt.gasLimit(),
                attempt.calldataHash(), attempt.signedTxCiphertext(), attempt.encryptionKeyVersion());
    }

    /**
     * 写入或更新 {@code markSubmitted} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void markSubmitted(UUID tenantId, UUID batchId, String txHash) {
        if (batchRepository.markSubmitted(tenantId, batchId, txHash) != 1) {
            throw new IllegalStateException("payout submission transition failed");
        }
        batchAttemptRepository.markSubmitted(tenantId, batchId, txHash);
        batchItemRepository.markSubmitted(tenantId, batchId);
        for (Map<String, Object> item : batchItemRepository.listByBatch(tenantId, batchId)) {
            withdrawalOrderRepository.markSent(tenantId,
                    ((Number) item.get("withdrawal_order_id")).longValue(), txHash);
        }
    }
    /**
     * 写入或更新 {@code markBroadcastUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void markBroadcastUnknown(UUID tenantId, UUID batchId, String code, String message) {
        String text = truncate(message, 1000);
        batchRepository.markBroadcastUnknown(tenantId, batchId, code, text);
        batchAttemptRepository.markUnknown(tenantId, batchId, code, text);
    }
    /**
     * 获取或查询 {@code listUnknownAttempts} 对应的数据，供调用方读取当前状态。
     */
    public List<UnknownAttempt> listUnknownAttempts(String chain, String network, int limit) {
        Set<String> batches = batchRepository.listBroadcastUnknown(chain, network).stream()
                .map(row -> row.get("tenant_id") + ":" + row.get("id"))
                .collect(java.util.stream.Collectors.toSet());
        return batchAttemptRepository.listUnknown(limit).stream()
                .filter(row -> batches.contains(row.get("tenant_id") + ":" + row.get("batch_id")))
                .map(row -> new UnknownAttempt((UUID) row.get("tenant_id"), (UUID) row.get("batch_id"),
                        (String) row.get("tx_hash"), (String) row.get("signed_tx_ciphertext"),
                        ((Number) row.get("rebroadcast_count")).intValue()))
                .limit(Math.min(Math.max(limit, 1), 100)).toList();
    }
    /**
     * 记录或保存 {@code recordRecoveryAttempt} 对应的数据，并遵守幂等和事务约束。
     */
    public void recordRecoveryAttempt(UnknownAttempt attempt) {
        batchAttemptRepository.recordRecovery(attempt.tenantId(), attempt.batchId(), attempt.txHash());
    }
    /**
     * 写入或更新 {@code markRecoveryError} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void markRecoveryError(UnknownAttempt attempt, String code, String message) {
        String text = truncate(message, 1000);
        batchAttemptRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), attempt.txHash(), code, text);
        batchRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), code, text);
    }
    /**
     * 获取或查询 {@code listPendingBatches} 对应的数据，供调用方读取当前状态。
     */
    public List<PendingBatch> listPendingBatches(String chain, String network, int limit) {
        return batchRepository.listPending(chain, network, limit).stream().map(row -> {
            int version = ((Number) row.get("delegate_version")).intValue();
            Map<String, Object> config = runtimeConfigRepository.find(chain, network, null, version)
                    .stream().findFirst().orElseThrow();
            return new PendingBatch((UUID) row.get("tenant_id"), (UUID) row.get("id"), chain,
                    (String) row.get("canonical_tx_hash"), (String) row.get("status"),
                    (String) row.get("hot_wallet"), ((Number) config.get("required_confirmations")).intValue());
        }).toList();
    }

    /** 查询没有广播交易的预广播失败批次，过滤掉已经写入签名尝试的批次。 */
    public List<UnbroadcastBatch> listFailedUnbroadcastBatches(String chain, String network, int limit) {
        return batchRepository.listFailedUnbroadcast(chain, network, limit).stream()
                .filter(row -> {
                    UUID tenantId = (UUID) row.get("tenant_id");
                    UUID batchId = (UUID) row.get("id");
                    return batchAttemptRepository.countByBatch(tenantId, batchId) == 0;
                })
                .map(row -> new UnbroadcastBatch(
                        (UUID) row.get("tenant_id"),
                        (UUID) row.get("id"),
                        String.valueOf(row.get("chain")),
                        String.valueOf(row.get("network")),
                        String.valueOf(row.get("error_code")),
                        (String) row.get("error_message")))
                .toList();
    }
    /**
     * 获取或查询 {@code listBatchItems} 对应的数据，供调用方读取当前状态。
     */
    public List<BatchItemIdentity> listBatchItems(UUID tenantId, UUID batchId) {
        return batchItemRepository.listByBatch(tenantId, batchId).stream().map(item -> {
            long orderId = ((Number) item.get("withdrawal_order_id")).longValue();
            Map<String, Object> order = withdrawalOrderRepository.findById(tenantId, orderId).orElseThrow();
            return new BatchItemIdentity(tenantId, batchId, ((Number) item.get("item_index")).intValue(), orderId,
                    (UUID) item.get("custody_withdrawal_id"), (String) order.get("order_no"),
                    Numeric.hexStringToByteArray((String) item.get("withdrawal_id_hash")),
                    (String) item.get("token_contract"), (String) item.get("recipient"),
                    ((BigDecimal) item.get("requested_amount_atomic")).toBigIntegerExact(),
                    (String) order.get("asset_symbol"), (BigDecimal) order.get("amount"),
                    (BigDecimal) order.get("fee"), (String) order.get("debit_account_id"),
                    (String) item.get("status"));
        }).toList();
    }

    /**
     * 写入或更新 {@code markItemResult} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markItemResult(UUID tenantId, UUID batchId, int itemIndex,
                              Evm7702PayoutReceiptParser.ItemResult result, String status) {
        return batchItemRepository.markResult(tenantId, batchId, itemIndex, result, status);
    }
    /**
     * 执行 {@code countFailedAttempts} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int countFailedAttempts(long withdrawalOrderId) {
        return batchItemRepository.countFailedAttempts(withdrawalOrderId);
    }
    /**
     * 写入或更新 {@code markWithdrawalRetrying} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalRetrying(BatchItemIdentity item, String error) {
        return withdrawalOrderRepository.markRetrying(item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000));
    }
    /**
     * 写入或更新 {@code markWithdrawalFailed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalFailed(BatchItemIdentity item, String error) {
        return withdrawalOrderRepository.markFailed(item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000));
    }

    /** 将未广播订单标记为最终失败，并允许调用方据返回值释放一次锁定余额。 */
    public int markUnbroadcastWithdrawalFailed(BatchItemIdentity item, String error) {
        return withdrawalOrderRepository.markPreBroadcastFailed(
                item.tenantId(), item.withdrawalOrderId(), truncate(error, 1000));
    }

    /** 判断未广播订单是否已经完成最终失败收敛。 */
    public boolean isUnbroadcastWithdrawalFailed(BatchItemIdentity item) {
        return withdrawalOrderRepository.findById(item.tenantId(), item.withdrawalOrderId())
                .map(row -> "FAILED".equals(row.get("status")) && row.get("tx_hash") == null)
                .orElse(false);
    }

    /** 将未广播批次项标记为最终失败，并保持批次审计记录。 */
    public int markUnbroadcastItemFailed(BatchItemIdentity item, String code) {
        return batchItemRepository.markPreBroadcastFailed(
                item.tenantId(), item.batchId(), item.itemIndex(), code);
    }

    /** 将未广播批次标记为失败，重复执行保持幂等。 */
    public int markUnbroadcastBatchFailed(UnbroadcastBatch batch, String code, String message) {
        return batchRepository.markFailedIfUnbroadcast(
                batch.tenantId(), batch.batchId(), code, truncate(message, 1000));
    }

    /**
     * 写入或更新 {@code markRevertedItem} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markRevertedItem(UUID tenantId, UUID batchId, int itemIndex, String status,
                                String errorHash) {
        return batchItemRepository.markReverted(tenantId, batchId, itemIndex, status, errorHash);
    }

    /**
     * 执行 {@code completeBatchMetadata} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void completeBatchMetadata(PendingBatch batch, String txHash,
                                      BigInteger gasUsed, BigInteger effectiveGasPrice,
                                      BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee,
                                      BigDecimal actualFee,
                                      BigInteger blockNumber, String blockHash,
                                      int failures, int itemCount, BigInteger operationNonce,
                                      String payoutDelegateAddress) {
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        String status = failures == 0 ? "CONFIRMED" : failures == itemCount ? "FAILED" : "PARTIAL_FAILED";
        if (batchRepository.complete(batch.tenantId(), batch.batchId(), txHash, status, gasUsed,
                effectiveGasPrice, l2Fee, l1Fee, operatorFee, totalFee, actualFee, blockNumber,
                blockHash, null, null) != 1) {
            throw new IllegalStateException("payout batch completion transition failed");
        }
        batchAttemptRepository.markConfirmed(batch.tenantId(), batch.batchId(), txHash);
        Map<String, Object> row = batchRepository.find(batch.tenantId(), batch.batchId()).stream()
                .findFirst().orElseThrow();
        payoutAccountRepository.markCompleted(batch.tenantId(), (String) row.get("chain"),
                Boolean.TRUE.equals(row.get("authorization_included")),
                numberOrNull(row.get("authorization_nonce")), operationNonce.add(BigInteger.ONE), txHash,
                payoutDelegateAddress, ((Number) row.get("delegate_version")).intValue());
    }

    /**
     * 执行 {@code completeRevertedBatchMetadata} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void completeRevertedBatchMetadata(PendingBatch batch, String txHash,
                                               BigInteger gasUsed, BigInteger effectiveGasPrice,
                                               BigInteger l2Fee, BigInteger l1Fee,
                                               BigInteger operatorFee, BigDecimal actualFee,
                                               BigInteger blockNumber,
                                               String blockHash, String errorHash) {
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        if (batchRepository.complete(batch.tenantId(), batch.batchId(), txHash, "FAILED", gasUsed,
                effectiveGasPrice, l2Fee, l1Fee, operatorFee, totalFee, actualFee, blockNumber,
                blockHash, "OUTER_REVERTED", errorHash) != 1) {
            throw new IllegalStateException("reverted payout batch completion transition failed");
        }
        batchAttemptRepository.markFailed(batch.tenantId(), batch.batchId(), txHash, "OUTER_REVERTED", errorHash);
        Map<String, Object> row = batchRepository.find(batch.tenantId(), batch.batchId()).stream()
                .findFirst().orElseThrow();
        String delegateAddress = runtimeConfigRepository.find((String) row.get("chain"),
                        (String) row.get("network"), null, ((Number) row.get("delegate_version")).intValue())
                .stream().findFirst().map(config -> (String) config.get("payout_delegate_address"))
                .orElseThrow();
        payoutAccountRepository.markReverted(batch.tenantId(), (String) row.get("chain"),
                Boolean.TRUE.equals(row.get("authorization_included")),
                numberOrNull(row.get("authorization_nonce")), numberOrNull(row.get("operation_nonce")),
                txHash, delegateAddress, ((Number) row.get("delegate_version")).intValue());
    }

    /**
     * 删除或释放 {@code releaseUnbroadcastBatch} 对应的资源，并收敛相关业务状态。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void releaseUnbroadcastBatch(Batch batch, String code, String message) {
        int attempts = batchAttemptRepository.countByBatch(batch.tenantId(), batch.id());
        if (attempts != 0) throw new IllegalStateException("signed payout batch cannot be released");
        String text = truncate(message, 1000);
        for (Map<String, Object> item : batchItemRepository.listCreated(batch.tenantId(), batch.id())) {
            if (withdrawalOrderRepository.markRetrying(batch.tenantId(),
                    ((Number) item.get("withdrawal_order_id")).longValue(), text) != 1) {
                throw new IllegalStateException("unbroadcast payout withdrawal retry transition failed");
            }
            batchItemRepository.markRetryable(batch.tenantId(), batch.id(),
                    ((Number) item.get("item_index")).intValue(), code);
        }
        if (batchRepository.markFailedIfLocked(batch.tenantId(), batch.id(), code, text) != 1) {
            throw new IllegalStateException("unbroadcast payout batch failure transition failed");
        }
    }
    /**
     * 校验 {@code requireBatchState} 对应的前置条件，不满足时抛出明确异常。
     */
    public BatchState requireBatchState(UUID tenantId, UUID batchId) {
        Map<String, Object> row = batchRepository.find(tenantId, batchId).stream()
                .findFirst().orElseThrow();
        return new BatchState(numberOrNull(row.get("operation_nonce")),
                ((Number) row.get("delegate_version")).intValue(),
                Boolean.TRUE.equals(row.get("authorization_included")));
    }

    /** 将数据库数值安全转换为大整数。 */
    private static BigInteger numberOrNull(Object value) {
        return value == null ? null : ((BigDecimal) value).toBigIntegerExact();
    }
    /**
     * 执行 {@code mapCandidate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private CandidateGroup mapCandidate(Map<String, Object> order, String chain, String network,
                                        Evm7702CollectionRepository.RuntimeConfig config,
                                        List<Map<String, Object>> assets,
                                        List<Map<String, Object>> tokens) {
        UUID tenantId = (UUID) order.get("tenant_id");
        String assetSymbol = String.valueOf(order.get("asset_symbol"));
        Map<String, Object> asset = assets.stream()
                .filter(row -> assetSymbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                .findFirst().orElse(null);
        if (asset == null) return null;
        boolean nativeAsset = Boolean.TRUE.equals(asset.get("native_asset"));
        Map<String, Object> token = tokens.stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> network.equals(String.valueOf(row.get("network"))))
                .filter(row -> assetSymbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled"))).findFirst().orElse(null);
        if (!nativeAsset && token == null) return null;
        ChainAddressRecord hot = chainAddressRepository.findEnabledByTenantAndAddress(tenantId, chain,
                String.valueOf(order.get("from_address"))).orElse(null);
        if (hot == null || !hasActiveGasAddress(tenantId, chain, String.valueOf(order.get("from_address")))) return null;
        return new CandidateGroup(tenantId, assetSymbol, String.valueOf(order.get("from_address")),
                nativeAsset ? "0x0000000000000000000000000000000000000000"
                        : String.valueOf(token.get("contract_address")),
                ((Number) asset.get("decimals")).intValue(), hot);
    }

    /** 判断租户链上提现来源是否为启用的 Gas 托管地址。 */
    private boolean hasActiveGasAddress(UUID tenantId, String chain, String address) {
        for (Map<String, Object> gas : gasAccountRepository.listActiveByTenantAndChain(tenantId, chain)) {
            Map<String, Object> custody = custodyAddressRepository.findByTenantAndId(tenantId,
                    (UUID) gas.get("custody_address_id")).orElse(null);
            if (custody != null && "ACTIVE".equals(custody.get("status"))
                    && address.equalsIgnoreCase(String.valueOf(custody.get("address")))) return true;
        }
        return false;
    }
    /**
     * 执行 {@code mapClaimedItem} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    /**
     * 处理 {@code withdrawalHash} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private static String withdrawalHash(UUID tenantId, UUID custodyWithdrawalId) {
        return Numeric.toHexString(Hash.sha3(
                (tenantId + ":WITHDRAWAL:" + custodyWithdrawalId).getBytes(StandardCharsets.UTF_8)));
    }
    /**
     * 转换或计算 {@code truncate} 对应的值，统一金额、格式和边界规则。
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record CandidateGroup(UUID tenantId, String assetSymbol, String hotWallet,
                                  String tokenContract, int decimals,
                                  ChainAddressRecord hotChainAddress) { }

    public record ClaimedItem(long withdrawalOrderId, UUID custodyWithdrawalId,
                              UUID custodyAddressId, String orderNo, String recipient,
                              BigDecimal amount, BigDecimal fee, BigInteger amountAtomic,
                              String debitAccountId, byte[] withdrawalId) {
        public ClaimedItem {
            withdrawalId = withdrawalId.clone();
        }

        /**
         * 处理 {@code withdrawalId} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
         */
        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }
    }

    public record Batch(UUID id, UUID tenantId, String chain, String network, String assetSymbol,
                        String tokenContract, int tokenDecimals, String hotWallet, String batchHash,
                        Instant signatureDeadline, ChainAddressRecord hotChainAddress,
                        Evm7702CollectionRepository.RuntimeConfig config,
                        List<ClaimedItem> items) { }

    public record PreparedAttempt(UUID tenantId, UUID batchId, long estimatedGas, long gasLimit,
                                  BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas,
                                  BigInteger relayerNonce, String txHash, String calldataHash,
                                  String signedTxCiphertext, String encryptionKeyVersion,
                                  boolean authorizationIncluded, BigInteger authorizationNonce,
                                  BigInteger operationNonce, Instant signatureDeadline) { }

    public record PendingBatch(UUID tenantId, UUID batchId, String chain, String txHash, String status,
                               String hotWallet, int requiredConfirmations) { }
    /** 没有签名尝试和链上交易的预广播失败批次。 */
    public record UnbroadcastBatch(UUID tenantId, UUID batchId, String chain, String network,
                                   String errorCode, String errorMessage) { }
    public record UnknownAttempt(UUID tenantId, UUID batchId, String txHash,
                                 String signedTxCiphertext, int rebroadcastCount) { }
    public record RuntimeTarget(String chain, String network, boolean active) { }
    public record BatchState(BigInteger operationNonce, int delegateVersion,
                             boolean authorizationIncluded) { }

    public record BatchItemIdentity(UUID tenantId, UUID batchId, int itemIndex, long withdrawalOrderId,
                                    UUID custodyWithdrawalId, String orderNo,
                                    byte[] withdrawalId, String token, String recipient,
                                    BigInteger amountAtomic, String assetSymbol,
                                    BigDecimal amount, BigDecimal fee, String debitAccountId,
                                    String status) {
        public BatchItemIdentity {
            withdrawalId = withdrawalId.clone();
        }

        /**
         * 处理 {@code withdrawalId} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
         */
        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }

    }
}
