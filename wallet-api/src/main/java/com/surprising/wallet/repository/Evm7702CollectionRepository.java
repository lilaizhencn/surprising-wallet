package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 负责钱包业务数据的查询、持久化、租户隔离和事务边界管理。
 */
@Component
public class Evm7702CollectionRepository {
    /**
     * 保存 {@code jdbc}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbc;
    /** EIP-7702 配置单表仓储。 */
    private final Evm7702ConfigRepository configRepository;
    /** EIP-7702 账户投影单表仓储。 */
    private final Evm7702AccountRepository accountRepository;
    /** 链地址单表仓储。 */
    private final ChainAddressRepository chainAddressRepository;
    /** 链配置单表仓储。 */
    private final ChainProfileRepository chainProfileRepository;
    /** 批量归集批次单表仓储。 */
    private final EvmCollectionBatchRepository batchRepository;
    /** 归集批次项单表仓储。 */
    private final EvmCollectionBatchItemRepository batchItemRepository;
    /** 归集批次尝试单表仓储。 */
    private final EvmCollectionBatchAttemptRepository batchAttemptRepository;
    /** 归集记录单表仓储。 */
    private final CollectionRecordRepository collectionRecordRepository;
    /** 托管地址单表仓储。 */
    private final CustodyAddressRepository custodyAddressRepository;
    /** Gas 账户单表仓储。 */
    private final CustodyGasAccountRepository gasAccountRepository;
    /** 链资产单表仓储。 */
    private final ChainAssetRepository chainAssetRepository;
    /** 代币配置单表仓储。 */
    private final TokenConfigRepository tokenConfigRepository;
    /**
     * 构造 {@code Evm7702CollectionRepository}，初始化该组件运行所需的状态和依赖。
     */
    public Evm7702CollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.configRepository = new Evm7702ConfigRepository(jdbc);
        this.accountRepository = new Evm7702AccountRepository(jdbc);
        this.chainAddressRepository = new ChainAddressRepository(jdbc);
        this.chainProfileRepository = new ChainProfileRepository(jdbc);
        this.batchRepository = new EvmCollectionBatchRepository(jdbc);
        this.batchItemRepository = new EvmCollectionBatchItemRepository(jdbc);
        this.batchAttemptRepository = new EvmCollectionBatchAttemptRepository(jdbc);
        this.collectionRecordRepository = new CollectionRecordRepository(jdbc);
        this.custodyAddressRepository = new CustodyAddressRepository(jdbc);
        this.gasAccountRepository = new CustodyGasAccountRepository(jdbc);
        this.chainAssetRepository = new ChainAssetRepository(jdbc);
        this.tokenConfigRepository = new TokenConfigRepository(jdbc);
    }

    /**
     * 构建或生成 {@code createAccountProjection} 对应的结果，并执行输入和状态校验。
     */
    public void createAccountProjection(UUID tenantId, UUID custodyAddressId, String chain,
                                        String network, String authorityAddress) {
        accountRepository.createProjection(tenantId, custodyAddressId, chain, network, authorityAddress);
    }
    /**
     * 获取或查询 {@code findRuntimeConfig} 对应的数据，供调用方读取当前状态。
     */
    public Optional<RuntimeConfig> findRuntimeConfig(String chain, String network, String status) {
        return configRepository.find(chain, network, status, null).stream()
                .map(this::mapConfig)
                .findFirst();
    }
    /**
     * 校验 {@code requireActiveConfig} 对应的前置条件，不满足时抛出明确异常。
     */
    public RuntimeConfig requireActiveConfig(String chain, String network) {
        return findRuntimeConfig(chain, network, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException(
                        "EIP-7702 ACTIVE configuration is missing for " + chain + "/" + network));
    }
    /**
     * 校验 {@code requireRuntimeConfigVersion} 对应的前置条件，不满足时抛出明确异常。
     */
    public RuntimeConfig requireRuntimeConfigVersion(String chain, String network, int version) {
        return configRepository.find(chain, network, null, version).stream()
                .map(this::mapConfig)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "EIP-7702 configuration version is missing for "
                                + chain + "/" + network + "/" + version));
    }
    /**
     * 获取或查询 {@code listRuntimeTargets} 对应的数据，供调用方读取当前状态。
     */
    public List<RuntimeTarget> listRuntimeTargets() {
        List<RuntimeTarget> targets = new ArrayList<>();
        List<Map<String, Object>> configs = configRepository.listAll();
        for (Map<String, Object> profile : chainProfileRepository.listAll()) {
            if (!Boolean.TRUE.equals(profile.get("enabled"))
                    || !"evm".equalsIgnoreCase(String.valueOf(profile.get("family")))) {
                continue;
            }
            String chain = String.valueOf(profile.get("chain"));
            String network = String.valueOf(profile.get("network"));
            boolean active = configs.stream().anyMatch(config -> chain.equals(config.get("chain"))
                    && network.equals(config.get("network")) && "ACTIVE".equals(config.get("status")));
            boolean managed = configs.stream().anyMatch(config -> chain.equals(config.get("chain"))
                    && network.equals(config.get("network"))
                    && Set.of("ACTIVE", "PAUSED").contains(config.get("status")));
            if (managed || batchRepository.existsPending(chain, network)) {
                targets.add(new RuntimeTarget(chain, network, active));
            }
        }
        return targets.stream().sorted(java.util.Comparator.comparing(RuntimeTarget::chain)
                .thenComparing(RuntimeTarget::network)).toList();
    }

    /** 将 EIP-7702 配置单表字段与链地址单表字段组合为运行时配置。 */
    private RuntimeConfig mapConfig(Map<String, Object> row) {
        long addressId = ((Number) row.get("relayer_chain_address_id")).longValue();
        ChainAddressRecord relayer = chainAddressRepository.findById(addressId)
                .map(ChainAddressRepository::mapRow)
                .orElseThrow(() -> new IllegalStateException("configured relayer chain address is missing"));
        String configuredRelayer = (String) row.get("relayer_address");
        if (configuredRelayer == null || !configuredRelayer.equalsIgnoreCase(relayer.getAddress())) {
            throw new IllegalStateException("configured relayer address does not match chain_address key path");
        }
        if (!Boolean.TRUE.equals(relayer.getEnabled())) {
            throw new IllegalStateException("configured EIP-7702 relayer chain_address is disabled");
        }
        return new RuntimeConfig((UUID) row.get("id"), (String) row.get("chain"), (String) row.get("network"),
                ((BigDecimal) row.get("chain_id")).toBigIntegerExact(), intValue(row.get("version")),
                (String) row.get("delegate_address"), (String) row.get("delegate_code_hash"),
                (String) row.get("collector_address"), (String) row.get("collector_code_hash"),
                (String) row.get("payout_delegate_address"), (String) row.get("payout_delegate_code_hash"),
                configuredRelayer, (String) row.get("status"), intValue(row.get("max_batch_items")),
                longValue(row.get("max_batch_gas")), (BigDecimal) row.get("block_gas_ratio"),
                (BigDecimal) row.get("gas_limit_multiplier"), intValue(row.get("signature_ttl_seconds")),
                intValue(row.get("required_confirmations")), Boolean.TRUE.equals(row.get("native_collection_enabled")),
                Boolean.TRUE.equals(row.get("batch_withdrawal_enabled")), intValue(row.get("withdrawal_max_wait_ms")),
                intValue(row.get("withdrawal_max_batch_items")), relayer);
    }

    /** 转换配置整数。 */
    private static int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    /** 转换配置长整数。 */
    private static long longValue(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
    /**
     * 获取或查询 {@code listUnknownAttempts} 对应的数据，供调用方读取当前状态。
     */
    public List<UnknownAttempt> listUnknownAttempts(String chain, String network, int limit) {
        Set<String> unknownBatches = batchRepository.listBroadcastUnknown(chain, network).stream()
                .map(row -> row.get("tenant_id") + ":" + row.get("id"))
                .collect(java.util.stream.Collectors.toSet());
        return batchAttemptRepository.listUnknown(limit).stream()
                .filter(row -> unknownBatches.contains(row.get("tenant_id") + ":" + row.get("batch_id")))
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
    public void markRecoveryError(UnknownAttempt attempt, String errorCode, String errorMessage) {
        String message = truncate(errorMessage, 1000);
        batchAttemptRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), attempt.txHash(),
                errorCode, message);
        batchRepository.markRecoveryError(attempt.tenantId(), attempt.batchId(), errorCode, message);
    }

    /**
     * 执行 {@code claimNextBatch} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public Optional<Batch> claimNextBatch(String chain, String network) {
        RuntimeConfig config = requireActiveConfig(chain, network);
        List<Map<String, Object>> assets = chainAssetRepository.listActiveByChain(chain);
        List<Map<String, Object>> tokenConfigs = tokenConfigRepository.listCollectEnabled(chain, network);
        CandidateGroup group = null;
        for (Map<String, Object> record : collectionRecordRepository.listClaimable(chain, 500)) {
            UUID tenantId = (UUID) record.get("tenant_id");
            UUID custodyId = (UUID) record.get("custody_address_id");
            String assetSymbol = String.valueOf(record.get("asset_symbol"));
            Map<String, Object> asset = assets.stream()
                    .filter(row -> assetSymbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                    .findFirst().orElse(null);
            if (asset == null) continue;
            Map<String, Object> custody = custodyAddressRepository.findByTenantAndId(tenantId, custodyId)
                    .orElse(null);
            if (custody == null || !"ACTIVE".equals(custody.get("status"))) continue;
            if (!accountRepository.exists(tenantId, custodyId, chain, network)) continue;
            Map<String, Object> hot = findHotAddress(tenantId, chain, String.valueOf(record.get("to_address")));
            if (hot == null) continue;
            boolean nativeAsset = Boolean.TRUE.equals(asset.get("native_asset"));
            Map<String, Object> token = tokenConfigs.stream()
                    .filter(row -> assetSymbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                    .findFirst().orElse(null);
            if ((!nativeAsset && token == null) || (nativeAsset && !config.nativeCollectionEnabled())) continue;
            group = new CandidateGroup(tenantId, assetSymbol, String.valueOf(record.get("to_address")),
                    nativeAsset ? "0x0000000000000000000000000000000000000000"
                            : String.valueOf(token.get("contract_address")),
                    ((Number) asset.get("decimals")).intValue());
            break;
        }
        if (group == null) {
            return Optional.empty();
        }

        List<ClaimedItem> items = new ArrayList<>();
        for (Map<String, Object> record : collectionRecordRepository.listClaimable(chain, config.maxBatchItems())) {
            if (!group.tenantId().equals(record.get("tenant_id"))
                    || !group.assetSymbol().equalsIgnoreCase(String.valueOf(record.get("asset_symbol")))
                    || !group.hotWallet().equalsIgnoreCase(String.valueOf(record.get("to_address")))) continue;
            UUID custodyId = (UUID) record.get("custody_address_id");
            if (!accountRepository.exists(group.tenantId(), custodyId, chain, network)) continue;
            Map<String, Object> custody = custodyAddressRepository.findByTenantAndId(group.tenantId(), custodyId)
                    .orElse(null);
            if (custody == null || !"ACTIVE".equals(custody.get("status"))) continue;
            Map<String, Object> address = chainAddressRepository.findByTenantAndId(group.tenantId(),
                    ((Number) custody.get("chain_address_id")).longValue()).orElse(null);
            if (address == null) continue;
            BigDecimal amount = (BigDecimal) record.get("amount");
            BigInteger atomic;
            try {
                atomic = amount.movePointRight(group.decimals()).toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new IllegalStateException("collection amount has more precision than token decimals", e);
            }
            if (atomic.signum() <= 0) throw new IllegalStateException("collection amount must be positive");
            items.add(new ClaimedItem(((Number) record.get("id")).longValue(),
                    (String) record.get("collection_no"), group.tenantId(), custodyId,
                    (String) record.get("from_address"), (String) record.get("to_address"), amount, atomic,
                    ChainAddressRepository.mapRow(address)));
            if (items.size() >= config.maxBatchItems()) break;
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }
        for (ClaimedItem item : items) {
            if (!item.tenantId().equals(group.tenantId())) {
                throw new IllegalStateException("cross-tenant collection batch is forbidden");
            }
        }

        UUID batchId = UUID.randomUUID();
        String batchHash = Numeric.toHexString(Hash.sha3(
                (group.tenantId() + ":" + batchId).getBytes(StandardCharsets.UTF_8)));
        batchRepository.insert(batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(), config.relayerAddress(),
                config.version(), batchHash, items.size());
        Instant signatureDeadline = Instant.now().plusSeconds(config.signatureTtlSeconds());
        for (int index = 0; index < items.size(); index++) {
            ClaimedItem item = items.get(index);
            if (batchItemRepository.insert(UUID.randomUUID(), group.tenantId(), batchId, index,
                    item.collectionRecordId(), item.custodyAddressId(), item.fromAddress(), group.tokenContract(),
                    group.hotWallet(), item.amountAtomic(), Timestamp.from(signatureDeadline)) != 1) {
                throw new IllegalStateException("collection batch item insert failed");
            }
            if (collectionRecordRepository.claimSigning(group.tenantId(), item.collectionRecordId()) != 1) {
                throw new IllegalStateException("collection item claim lost");
            }
        }
        return Optional.of(new Batch(
                batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(), batchHash,
                signatureDeadline, config, List.copyOf(items)));
    }

    /**
     * 记录或保存 {@code saveSignedAttempt} 对应的数据，并遵守幂等和事务约束。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void saveSignedAttempt(Batch batch, PreparedAttempt attempt) {
        if (!batch.tenantId().equals(attempt.tenantId()) || !batch.id().equals(attempt.batchId())) {
            throw new IllegalArgumentException("attempt tenant/batch mismatch");
        }
        if (attempt.items().size() != batch.items().size()) {
            throw new IllegalArgumentException("prepared item count mismatch");
        }
        if (batchRepository.markSigning(batch.tenantId(), batch.id(), attempt.estimatedGas(), attempt.gasLimit(),
                attempt.maxFeePerGas(), attempt.maxPriorityFeePerGas()) != 1) {
            throw new IllegalStateException("batch is not lock-owned for signing");
        }
        for (int index = 0; index < attempt.items().size(); index++) {
            PreparedItem item = attempt.items().get(index);
            if (item.itemIndex() != index) {
                throw new IllegalArgumentException("prepared item indexes must be contiguous");
            }
            if (batchItemRepository.markSigned(batch.tenantId(), batch.id(), index, item.authorityAddress(),
                    item.authorizationIncluded(), item.authorizationNonce(), item.operationNonce(),
                    Timestamp.from(item.signatureDeadline()), item.callGasLimit()) != 1) {
                throw new IllegalStateException("prepared item does not match claimed item");
            }
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
            throw new IllegalStateException("batch submission transition failed");
        }
        batchAttemptRepository.markSubmitted(tenantId, batchId, txHash);
        batchItemRepository.markSubmitted(tenantId, batchId);
        for (Long collectionId : batchItemRepository.listCollectionRecordIds(tenantId, batchId)) {
            collectionRecordRepository.markSent(tenantId, collectionId, txHash);
        }
    }
    /**
     * 写入或更新 {@code markBroadcastUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void markBroadcastUnknown(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        String message = truncate(errorMessage, 1000);
        batchRepository.markBroadcastUnknown(tenantId, batchId, errorCode, message);
        batchAttemptRepository.markUnknown(tenantId, batchId, errorCode, message);
    }
    /**
     * 获取或查询 {@code findPendingBatch} 对应的数据，供调用方读取当前状态。
     */
    public Optional<PendingBatch> findPendingBatch(UUID tenantId, UUID batchId) {
        return batchRepository.find(tenantId, batchId).stream().map(row -> {
            String chain = (String) row.get("chain");
            String network = (String) row.get("network");
            int version = intValue(row.get("delegate_version"));
            Map<String, Object> config = configRepository.find(chain, network, null, version).stream()
                    .findFirst().orElseThrow();
            return new PendingBatch((UUID) row.get("tenant_id"), (UUID) row.get("id"),
                    (String) row.get("canonical_tx_hash"), (String) row.get("status"),
                    intValue(config.get("required_confirmations")), (String) config.get("collector_address"));
        }).findFirst();
    }
    /**
     * 获取或查询 {@code listPendingBatches} 对应的数据，供调用方读取当前状态。
     */
    public List<PendingBatch> listPendingBatches(String chain, String network, int limit) {
        return batchRepository.listPending(chain, network, limit).stream().map(row -> {
            int version = intValue(row.get("delegate_version"));
            Map<String, Object> config = configRepository.find(chain, network, null, version).stream()
                    .findFirst().orElseThrow();
            return new PendingBatch((UUID) row.get("tenant_id"), (UUID) row.get("id"),
                    (String) row.get("canonical_tx_hash"), (String) row.get("status"),
                    intValue(config.get("required_confirmations")), (String) config.get("collector_address"));
        }).toList();
    }
    /**
     * 获取或查询 {@code listBatchItemIdentities} 对应的数据，供调用方读取当前状态。
     */
    public List<BatchItemIdentity> listBatchItemIdentities(UUID tenantId, UUID batchId) {
        return batchItemRepository.listIdentities(tenantId, batchId).stream()
                .map(row -> new BatchItemIdentity(((Number) row.get("item_index")).intValue(),
                        (String) row.get("authority_address"), (String) row.get("token_contract"),
                        (String) row.get("recipient"), ((BigDecimal) row.get("requested_amount_atomic")).toBigIntegerExact()))
                .toList();
    }

    /**
     * 执行 {@code completeBatch} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void completeBatch(UUID tenantId, UUID batchId, String txHash,
                              BigInteger gasUsed, BigInteger effectiveGasPrice,
                              BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee,
                              BigDecimal actualFee,
                              BigInteger blockNumber, String blockHash,
                              List<com.surprising.wallet.chain.evm.Evm7702ReceiptParser.ItemResult> results) {
        List<BatchItemIdentity> expected = listBatchItemIdentities(tenantId, batchId);
        if (expected.size() != results.size()) {
            throw new IllegalStateException("receipt result count does not match batch");
        }
        int failures = 0;
        for (int index = 0; index < results.size(); index++) {
            BatchItemIdentity identity = expected.get(index);
            var result = results.get(index);
            if (identity.itemIndex() != result.itemIndex()
                    || !identity.authority().equalsIgnoreCase(result.authority())
                    || !identity.token().equalsIgnoreCase(result.token())
                    || !identity.recipient().equalsIgnoreCase(result.recipient())
                    || !identity.amount().equals(result.requestedAmount())) {
                throw new IllegalStateException("receipt item identity does not match persisted batch");
            }
            String itemStatus = result.success() ? "CONFIRMED" : "RETRYABLE";
            if (!result.success()) failures++;
            if (batchItemRepository.complete(tenantId, batchId, index, result.actualReceived(), itemStatus,
                    (long) result.logIndex(), result.success() ? null : result.errorHash()) != 1) {
                throw new IllegalStateException("batch item completion transition failed");
            }
            String collectionStatus = result.success() ? "CONFIRMED" : "RETRYING";
            Map<String, Object> itemRow = batchItemRepository.findForCompletion(tenantId, batchId, index)
                    .stream().findFirst().orElseThrow();
            long collectionId = ((Number) itemRow.get("collection_record_id")).longValue();
            collectionRecordRepository.updateExecution(tenantId, collectionId, collectionStatus, txHash,
                    result.success() ? null : "EIP-7702 item execution failed: " + result.errorHash());
            Map<String, Object> batchRow = batchRepository.find(tenantId, batchId).stream()
                    .findFirst().orElseThrow();
            int version = intValue(batchRow.get("delegate_version"));
            Map<String, Object> configRow = configRepository.find((String) batchRow.get("chain"),
                    (String) batchRow.get("network"), null, version).stream().findFirst().orElseThrow();
            if (accountRepository.markCollectionCompleted(tenantId, (UUID) itemRow.get("custody_address_id"),
                    Boolean.TRUE.equals(itemRow.get("authorization_included")), txHash,
                    (String) configRow.get("delegate_address"), version,
                    longValue(itemRow.get("operation_nonce"))) != 1) {
                throw new IllegalStateException("EIP-7702 account projection completion failed");
            }
        }
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        String batchStatus = failures == 0 ? "CONFIRMED"
                : failures == results.size() ? "FAILED" : "PARTIAL_FAILED";
        if (batchRepository.complete(tenantId, batchId, txHash, batchStatus, gasUsed, effectiveGasPrice,
                l2Fee, l1Fee, operatorFee, totalFee, actualFee, blockNumber, blockHash) != 1) {
            throw new IllegalStateException("batch completion transition failed");
        }
        batchAttemptRepository.markConfirmed(tenantId, batchId, txHash);
    }

    /**
     * 删除或释放 {@code releaseUnbroadcastBatch} 对应的资源，并收敛相关业务状态。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void releaseUnbroadcastBatch(Batch batch, String errorCode, String errorMessage) {
        int attempts = batchAttemptRepository.countByBatch(batch.tenantId(), batch.id());
        if (attempts != 0) {
            throw new IllegalStateException("signed/outbox batch cannot be released as unbroadcast");
        }
        String message = truncate(errorMessage, 1000);
        if (batchRepository.markFailedIfUnbroadcast(batch.tenantId(), batch.id(), errorCode, message) != 1) {
            throw new IllegalStateException("unbroadcast batch failure transition failed");
        }
        for (Map<String, Object> item : batchItemRepository.listForRelease(batch.tenantId(), batch.id())) {
            long collectionId = ((Number) item.get("collection_record_id")).longValue();
            String status = batchItemRepository.countFailedHistory(batch.tenantId(), collectionId) >= 3
                    ? "FAILED" : "RETRYABLE";
            if (batchItemRepository.markReleased(batch.tenantId(), batch.id(),
                    ((Number) item.get("item_index")).intValue(), status, errorCode) != 1) {
                throw new IllegalStateException("unbroadcast batch item transition failed");
            }
            collectionRecordRepository.updateExecution(batch.tenantId(), collectionId,
                    "FAILED".equals(status) ? "FAILED" : "RETRYING", null, message);
        }
    }

    /** 查询指定租户链上的启用 Gas 热地址。 */
    private Map<String, Object> findHotAddress(UUID tenantId, String chain, String address) {
        for (Map<String, Object> gas : gasAccountRepository.listActiveByTenantAndChain(tenantId, chain)) {
            UUID custodyId = (UUID) gas.get("custody_address_id");
            Map<String, Object> hot = custodyAddressRepository.findByTenantAndId(tenantId, custodyId)
                    .orElse(null);
            if (hot != null && "ACTIVE".equals(hot.get("status"))
                    && address.equalsIgnoreCase(String.valueOf(hot.get("address")))) {
                return hot;
            }
        }
        return null;
    }
    /**
     * 执行 {@code mapConfig} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private RuntimeConfig mapConfig(ResultSet rs) throws SQLException {
        String configuredRelayer = rs.getString("relayer_address");
        String derivedRelayer = rs.getString("address");
        if (!configuredRelayer.equalsIgnoreCase(derivedRelayer)) {
            throw new IllegalStateException("configured relayer address does not match chain_address key path");
        }
        if (!rs.getBoolean("enabled")) {
            throw new IllegalStateException("configured EIP-7702 relayer chain_address is disabled");
        }
        ChainAddressRecord relayer = ChainAddressRecord.builder()
                .id(rs.getLong("relayer_chain_address_id"))
                .chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol"))
                .accountId(rs.getString("account_id"))
                .userId(rs.getLong("user_id"))
                .biz(rs.getInt("biz"))
                .addressIndex(rs.getLong("address_index"))
                .address(derivedRelayer)
                .ownerAddress(rs.getString("owner_address"))
                .derivationPath(rs.getString("derivation_path"))
                .walletRole(rs.getString("wallet_role"))
                .enabled(true)
                .build();
        return new RuntimeConfig(
                rs.getObject("id", UUID.class), rs.getString("chain"), rs.getString("network"),
                rs.getBigDecimal("chain_id").toBigIntegerExact(), rs.getInt("version"),
                rs.getString("delegate_address"), rs.getString("delegate_code_hash"),
                rs.getString("collector_address"), rs.getString("collector_code_hash"),
                rs.getString("payout_delegate_address"), rs.getString("payout_delegate_code_hash"),
                configuredRelayer, rs.getString("status"), rs.getInt("max_batch_items"),
                rs.getLong("max_batch_gas"), rs.getBigDecimal("block_gas_ratio"),
                rs.getBigDecimal("gas_limit_multiplier"), rs.getInt("signature_ttl_seconds"),
                rs.getInt("required_confirmations"), rs.getBoolean("native_collection_enabled"),
                rs.getBoolean("batch_withdrawal_enabled"), rs.getInt("withdrawal_max_wait_ms"),
                rs.getInt("withdrawal_max_batch_items"), relayer);
    }
    /**
     * 执行 {@code mapClaimedItem} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ClaimedItem mapClaimedItem(ResultSet rs, int decimals) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigInteger atomic;
        try {
            atomic = amount.movePointRight(decimals).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("collection amount has more precision than token decimals", e);
        }
        if (atomic.signum() <= 0) {
            throw new IllegalStateException("collection amount must be positive");
        }
        ChainAddressRecord authority = ChainAddressRecord.builder()
                .id(rs.getLong("chain_address_id"))
                .chain(rs.getString("native_chain"))
                .assetSymbol(rs.getString("native_symbol"))
                .accountId(rs.getString("account_id"))
                .userId(rs.getLong("user_id"))
                .biz(rs.getInt("biz"))
                .addressIndex(rs.getLong("address_index"))
                .address(rs.getString("from_address"))
                .ownerAddress(rs.getString("owner_address"))
                .derivationPath(rs.getString("derivation_path"))
                .walletRole(rs.getString("wallet_role"))
                .enabled(rs.getBoolean("enabled"))
                .build();
        return new ClaimedItem(
                rs.getLong("collection_record_id"), rs.getString("collection_no"),
                rs.getObject("tenant_id", UUID.class), rs.getObject("custody_address_id", UUID.class),
                rs.getString("from_address"), rs.getString("to_address"), amount, atomic, authority);
    }
    /**
     * 转换或计算 {@code truncate} 对应的值，统一金额、格式和边界规则。
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record RuntimeConfig(
            UUID id, String chain, String network, BigInteger chainId, int version,
            String delegateAddress, String delegateCodeHash, String collectorAddress,
            String collectorCodeHash, String payoutDelegateAddress, String payoutDelegateCodeHash,
            String relayerAddress, String status,
            int maxBatchItems, long maxBatchGas, BigDecimal blockGasRatio,
            BigDecimal gasLimitMultiplier, int signatureTtlSeconds,
            int requiredConfirmations, boolean nativeCollectionEnabled,
            boolean batchWithdrawalEnabled, int withdrawalMaxWaitMs,
            int withdrawalMaxBatchItems, ChainAddressRecord relayerChainAddress) {
    }

    private record CandidateGroup(
            UUID tenantId, String assetSymbol, String hotWallet,
            String tokenContract, int decimals) {
    }

    public record ClaimedItem(
            long collectionRecordId, String collectionNo, UUID tenantId,
            UUID custodyAddressId, String fromAddress, String toAddress,
            BigDecimal amount, BigInteger amountAtomic, ChainAddressRecord authorityChainAddress) {
    }

    public record Batch(
            UUID id, UUID tenantId, String chain, String network, String assetSymbol,
            String tokenContract, int tokenDecimals, String hotWallet, String batchHash,
            Instant signatureDeadline, RuntimeConfig config, List<ClaimedItem> items) {
    }

    public record PreparedItem(
            int itemIndex, String authorityAddress, boolean authorizationIncluded,
            BigInteger authorizationNonce, BigInteger operationNonce,
            Instant signatureDeadline, long callGasLimit) {
    }

    public record PreparedAttempt(
            UUID tenantId, UUID batchId, long estimatedGas, long gasLimit,
            BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas,
            BigInteger relayerNonce, String txHash, String calldataHash,
            String signedTxCiphertext, String encryptionKeyVersion,
            List<PreparedItem> items) {
    }

    public record PendingBatch(
            UUID tenantId, UUID batchId, String txHash, String status,
            int requiredConfirmations, String collectorAddress) {
    }

    public record UnknownAttempt(
            UUID tenantId, UUID batchId, String txHash,
            String signedTxCiphertext, int rebroadcastCount) {
    }
    public record RuntimeTarget(String chain, String network, boolean active) {
    }

    public record BatchItemIdentity(
            int itemIndex, String authority, String token, String recipient, BigInteger amount) {
    }
}
