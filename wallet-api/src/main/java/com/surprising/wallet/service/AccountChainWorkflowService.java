package com.surprising.wallet.service;

import com.surprising.wallet.chain.aptos.AptosTransactionService;
import com.surprising.wallet.chain.cardano.CardanoTransactionService;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.hypercore.HyperCoreTransactionService;
import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.chain.monero.MoneroTransactionService;
import com.surprising.wallet.chain.near.NearTransactionService;
import com.surprising.wallet.chain.polkadot.PolkadotTransactionService;
import com.surprising.wallet.chain.solana.SolanaTransactionService;
import com.surprising.wallet.chain.sui.SuiTransactionService;
import com.surprising.wallet.chain.ton.TonTransactionService;
import com.surprising.wallet.chain.xrp.XrpTransactionService;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.Evm7702ConfigRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账户链工作流服务，编排多链的充值扫描、提现签名/确认、归集与归集确认的完整流程。
 *
 * <p>支持的链族：EVM（ETH、BASE、BNB、POLYGON、ARBITRUM、OPTIMISM、AVAX_C 等）、
 * Solana、Aptos、Sui、TON、XRP、Cardano、Polkadot、Monero、NEAR、HyperCore、TRON。</p>
 *
 * <p>核心调度方法：</p>
 * <ul>
 *   <li>{@link #scanDeposits()} — 批量扫描所有已启用账户链的充值</li>
 *   <li>{@link #processWithdrawals()} — 批量处理待签名的提现单</li>
 *   <li>{@link #confirmWithdrawals()} — 批量确认已发送的提现交易</li>
 *   <li>{@link #processCollections()} — 批量创建并签名归集交易</li>
 *   <li>{@link #confirmCollections()} — 批量确认归集交易</li>
 * </ul>
 *
 * @see WalletRuntimeConfigService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountChainWorkflowService {
    /** 每次提现最多处理 20 笔 */
    private static final int WITHDRAW_LIMIT = 20;
    /** 每次确认最多处理 50 笔 */
    private static final int CONFIRM_LIMIT = 50;
    /** 每次归集最多处理 20 笔 */
    private static final int COLLECTION_LIMIT = 20;
    /** 签名状态过期时间 10 分钟 */
    private static final Duration SIGNING_STALE_TIMEOUT = Duration.ofMinutes(10);
    /** 账户链调度优先级列表：XMR 和 HYPERCORE 最优先，其余链按列表顺序 */
    private static final List<String> ACCOUNT_CHAIN_PRIORITY = List.of(
            "XMR",
            "HYPERCORE",
            "ETH", "BASE", "BNB", "POLYGON", "ARBITRUM", "OPTIMISM", "AVAX_C", "HYPEREVM",
            "LINEA", "SCROLL", "UNICHAIN",
            "X_LAYER", "ROBINHOOD_CHAIN",
            "SOLANA", "TRON", "XRP", "ADA", "TON", "APTOS", "SUI", "NEAR");

    private final ChainProfileRepository chainProfileRepository;
    private final Evm7702ConfigRepository evm7702ConfigRepository;
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CollectionRecordRepository collectionRecordRepository;
    private final WithdrawalClaimService withdrawalClaimService;
    private final EvmBatchOwnershipService evmBatchOwnershipService;
    private final CollectionPlanningService collectionPlanningService;
    private final TokenConfigRepository tokenConfigRepository;
    private final ChainAddressRepository chainAddressRepository;

    private final WalletRuntimeConfigService runtimeConfigService;
    private final AccountChainDepositWorkflow depositWorkflow;
    private final AccountChainAssetService assets;
    private final TronAccountChainService tronWorkflow;
    /** 各账户链下次允许扫描的时间，避免快速链和慢速链共用同一 RPC 轮询周期。 */
    private final java.util.concurrent.ConcurrentMap<String, Long> nextDepositScanAtMillis =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** EVM 链交易服务 */
    private final EvmAccountTransactionService evmTransactionService;
    /** HyperCore 链交易服务 */
    private final HyperCoreTransactionService hyperCoreTransactionService;
    /** Solana 链交易服务 */
    private final SolanaTransactionService solanaTransactionService;
    /** Aptos 链交易服务 */
    private final AptosTransactionService aptosTransactionService;
    /** Sui 链交易服务 */
    private final SuiTransactionService suiTransactionService;
    /** TON 链交易服务 */
    private final TonTransactionService tonTransactionService;
    /** XRP 链交易服务 */
    private final XrpTransactionService xrpTransactionService;
    /** Cardano 链交易服务 */
    private final CardanoTransactionService cardanoTransactionService;
    /** Monero 链交易服务 */
    private final MoneroTransactionService moneroTransactionService;
    /** NEAR 链交易服务 */
    private final NearTransactionService nearTransactionService;
    /** Polkadot 链交易服务 */
    private final PolkadotTransactionService polkadotTransactionService;
    private final WithdrawalSettlementService settlements;

    /**
     * Monero 专用工作流：扫描 -> 提现 -> 确认提现 -> 归集 -> 确认归集。
     */
    public void moneroWorkflow() {
        AccountChainProfile profile = chainProfileRepository.findAccountByChain("XMR")
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                .orElse(null);
        if (profile == null) {
            return;
        }
        processSingleAccountChain(profile);
    }

    /** 批量扫描所有已启用账户链的充值。 */
    public void scanDeposits() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            depositWorkflow.scan(profile);
        }
    }

    /**
     * 仅扫描已到期的账户链。全局扫描开关关闭时清空节流状态，重新开启后下一轮立即生效。
     * XMR 有独立串行任务，避免与通用账户链任务重复扫描同一钱包。
     */
    public void scanDueDeposits() {
        if (!runtimeConfigService.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN)) {
            nextDepositScanAtMillis.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            String chain = profile.getChain();
            if ("XMR".equalsIgnoreCase(chain)) {
                continue;
            }
            if (!Boolean.TRUE.equals(profile.getScanEnabled())) {
                nextDepositScanAtMillis.remove(chain);
                continue;
            }
            if (now < nextDepositScanAtMillis.getOrDefault(chain, 0L)) {
                continue;
            }
            depositWorkflow.scan(profile);
            nextDepositScanAtMillis.put(
                    chain, System.currentTimeMillis() + runtimeConfigService.scanIntervalMillis(chain));
        }
    }

    /** 批量处理所有已启用账户链的待签名提现。 */
    public void processWithdrawals() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            processWithdrawals(profile);
        }
    }

    /** 批量确认所有已启用账户链的已发送提现。 */
    public void confirmWithdrawals() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            confirmWithdrawals(profile);
        }
    }

    /** 批量创建并签名所有已启用账户链的归集交易。 */
    public void processCollections() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            processCollections(profile);
        }
    }

    /** 批量确认所有已启用账户链的归集交易。 */
    public void confirmCollections() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            confirmCollections(profile);
        }
    }
    private void processSingleAccountChain(AccountChainProfile profile) {
        depositWorkflow.scan(profile);
        processWithdrawals(profile);
        confirmWithdrawals(profile);
        processCollections(profile);
        confirmCollections(profile);
    }

    private void processWithdrawals(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_WITHDRAW)) {
            return;
        }
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && evm7702ConfigRepository.existsManagedBatchWithdrawal(profile.getChain(), profile.getNetwork())) {
            return;
        }
        int stale = withdrawalOrderRepository.markStaleSigningUnknown(profile.getChain(), Instant.now().minus(SIGNING_STALE_TIMEOUT));
        if (stale > 0) {
            log.warn("marked stale signing withdrawals as broadcast-unknown: chain={} count={}",
                    profile.getChain(), stale);
        }
        for (WithdrawalOrderRecord order : withdrawalOrderRepository.listForSigning(profile.getChain(), WITHDRAW_LIMIT)) {
            try {
                processWithdrawal(profile, order);
            } catch (Exception e) {
                log.warn("account-chain withdrawal failed: chain={} orderNo={} error={}",
                        order.getChain(), order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
    private void confirmWithdrawals(AccountChainProfile profile) {
        for (WithdrawalOrderRecord order : withdrawalOrderRepository.listByStatus(profile.getChain(), "SENT", CONFIRM_LIMIT)) {
            try {
                confirmWithdrawal(profile, order);
            } catch (Exception e) {
                log.warn("account-chain withdrawal confirmation failed: chain={} orderNo={} error={}",
                        order.getChain(), order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
    private void processCollections(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_COLLECTION)) {
            return;
        }
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && evm7702ConfigRepository.existsManaged(profile.getChain(), profile.getNetwork())
                && !evm7702ConfigRepository.existsActive(profile.getChain(), profile.getNetwork())) {
            return;
        }
        createCollectionCandidates(profile);
        for (ChainCollectionRecord record : collectionRecordRepository.listForSigning(profile.getChain(), COLLECTION_LIMIT)) {
            try {
                processCollection(profile, record);
            } catch (Exception e) {
                collectionRecordRepository.updateStatus(
                        record.getTenantId(),
                        record.getChain(),
                        record.getCollectionNo(),
                        "FAILED",
                        null,
                        e.getMessage(),
                        null);
                log.warn("account-chain collection failed: chain={} collectionNo={} error={}",
                        record.getChain(), record.getCollectionNo(), e.getMessage(), e);
            }
        }
    }
    private void confirmCollections(AccountChainProfile profile) {
        for (ChainCollectionRecord record : collectionRecordRepository.listByStatus(profile.getChain(), "SENT", CONFIRM_LIMIT)) {
            try {
                confirmCollection(profile, record);
            } catch (Exception e) {
                log.warn("account-chain collection confirmation failed: chain={} collectionNo={} error={}",
                        record.getChain(), record.getCollectionNo(), e.getMessage(), e);
            }
        }
    }
    private void processWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order) {
        UUID tenantId = Objects.requireNonNull(order.getTenantId(), "withdrawal tenantId is required");
        ChainAddressRecord from = assets.requireAddress(tenantId, order.getChain(), order.getAssetSymbol(), order.getFromAddress());
        if (withdrawalClaimService.claim(tenantId, order.getChain(), order.getOrderNo(), from.getAddress()) != 1) {
            return;
        }
        try {
            String txHash = dispatchWithdrawal(profile, order, from);
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("withdrawal broadcast returned empty tx hash");
            }
            if (withdrawalOrderRepository.markSent(tenantId, order.getChain(), order.getOrderNo(), from.getAddress(), txHash) != 1) {
                throw new IllegalStateException("withdrawal state changed before SENT: " + order.getOrderNo());
            }
        } catch (Exception e) {
            withdrawalOrderRepository.markBroadcastUnknown(tenantId, order.getChain(), order.getOrderNo(), from.getAddress(), e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    private String dispatchWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order,
                                      ChainAddressRecord from) throws Exception {
        String chain = profile.getChain();
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (assets.isNative(profile, order.getAssetSymbol())) {
                return evmTransactionService.sendNative(chain, from, order.getToAddress(), order.getAmount());
            }
            TokenDefinition token = assets.requireToken(chain, order.getAssetSymbol());
            return evmTransactionService.sendToken(chain, from, token, order.getToAddress(), order.getAmount());
        }
        return switch (chain) {
            case "SOLANA" -> {
                if (assets.isNative(profile, order.getAssetSymbol())) {
                    yield solanaTransactionService.sendNative(
                            from, order.getToAddress(), assets.toAtomicLong(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())));
                }
                TokenDefinition token = assets.requireToken(chain, order.getAssetSymbol());
                yield solanaTransactionService.sendTokenAmount(
                        from, token.getContractAddress(), order.getToAddress(), order.getAmount(), token.getDecimals());
            }
            case "APTOS" -> assets.isNative(profile, order.getAssetSymbol())
                    ? aptosTransactionService.sendNative(from, order.getToAddress(),
                    assets.toAtomicLong(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())))
                    : aptosTransactionService.sendToken(from, assets.requireToken(chain, order.getAssetSymbol()),
                    order.getToAddress(), assets.toAtomicLong(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())));
            case "SUI" -> assets.isNative(profile, order.getAssetSymbol())
                    ? suiTransactionService.sendNative(from, order.getToAddress(),
                    assets.toAtomicLong(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())))
                    : suiTransactionService.sendCoin(from, assets.requireToken(chain, order.getAssetSymbol()).getContractAddress(),
                    order.getToAddress(), assets.toAtomicLong(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())));
            case "TON" -> assets.isNative(profile, order.getAssetSymbol())
                    ? broadcastTonNative(order, from)
                    : broadcastTonJetton(order, from, assets.requireToken(chain, order.getAssetSymbol()));
            case "XRP" -> assets.isNative(profile, order.getAssetSymbol())
                    ? xrpTransactionService.sendNative(from, order.getToAddress(), order.getAmount())
                    : xrpTransactionService.sendIssuedCurrency(
                    from, assets.requireToken(chain, order.getAssetSymbol()), order.getToAddress(), order.getAmount());
            case "ADA" -> {
                if (assets.isNative(profile, order.getAssetSymbol())) {
                    yield cardanoTransactionService.sendNative(from, order.getToAddress(),
                            assets.toAtomicBigInteger(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())));
                }
                yield cardanoTransactionService.sendToken(from, assets.requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "DOT" -> {
                if (assets.isNative(profile, order.getAssetSymbol())) {
                    yield polkadotTransactionService.sendNative(from, order.getToAddress(),
                            assets.toAtomicBigInteger(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())));
                }
                yield polkadotTransactionService.sendAsset(from, assets.requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "XMR" -> {
                if (!assets.isNative(profile, order.getAssetSymbol())) {
                    throw new IllegalStateException("Monero tokens are not supported");
                }
                yield moneroTransactionService.sendNative(profile, from, order.getToAddress(), order.getAmount());
            }
            case "NEAR" -> {
                if (assets.isNative(profile, order.getAssetSymbol())) {
                    yield nearTransactionService.sendNative(from, order.getToAddress(),
                            assets.toAtomicBigInteger(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())));
                }
                yield nearTransactionService.sendToken(from, assets.requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "HYPERCORE" -> {
                if (assets.isNative(profile, order.getAssetSymbol())) {
                    yield hyperCoreTransactionService.sendUsd(profile, from, order.getToAddress(), order.getAmount());
                }
                yield hyperCoreTransactionService.sendSpot(profile, from, assets.requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "TRON" -> tronWorkflow.broadcast(profile, order, from);
            default -> throw new IllegalStateException("unsupported account-chain withdrawal: " + chain);
        };
    }
    private void confirmWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order) throws Exception {
        UUID tenantId = Objects.requireNonNull(order.getTenantId(), "withdrawal tenantId is required");
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && evmBatchOwnershipService.withdrawalPending(tenantId, order.getId())) {
            return;
        }
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            evmTransactionService.confirmWithdrawal(tenantId, order.getChain(), order.getOrderNo());
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> solanaTransactionService.confirmWithdrawal(tenantId, order.getOrderNo());
            case "APTOS" -> aptosTransactionService.confirmWithdrawal(tenantId, order.getOrderNo());
            case "SUI" -> suiTransactionService.confirmWithdrawal(tenantId, order.getOrderNo());
            case "TON" -> confirmTonWithdrawal(order);
            case "XRP" -> xrpTransactionService.confirmWithdrawal(tenantId, profile, order.getOrderNo());
            case "ADA" -> cardanoTransactionService.confirmWithdrawal(tenantId, profile,
                    order.getOrderNo(), order.getTxHash());
            case "DOT" -> polkadotTransactionService.confirmWithdrawal(tenantId, profile,
                    order.getOrderNo(), order.getTxHash(), order.getAssetSymbol());
            case "XMR" -> moneroTransactionService.confirmWithdrawal(tenantId, profile,
                    order.getOrderNo(), order.getTxHash(), order.getToAddress(), order.getAmount());
            case "NEAR" -> nearTransactionService.confirmWithdrawal(tenantId,
                    order.getOrderNo(), order.getTxHash(), order.getDebitAccountId());
            case "HYPERCORE" -> hyperCoreTransactionService.confirmWithdrawal(tenantId,
                    order.getOrderNo(), order.getTxHash());
            case "TRON" -> tronWorkflow.confirmWithdrawal(profile, order);
            default -> {
            }
        }
    }
    private void createCollectionCandidates(AccountChainProfile profile) {
        List<CollectionCandidateRecord> candidates = collectionPlanningService.candidates(profile.getChain(), BigDecimal.ZERO, COLLECTION_LIMIT);
        if (candidates.isEmpty()) {
            return;
        }
        BigDecimal evmFeeReserve = "evm".equalsIgnoreCase(profile.getFamily())
                ? evmTransactionService.estimateCollectionFeeReserve(

                        profile.getChain(),
                        chainProfileRepository.findAccountByChain(profile.getChain()).map(configuredNetworkProfile -> tokenConfigRepository.listDefinitions(profile.getChain(), configuredNetworkProfile.getNetwork())).orElse(List.of()).size())
                : BigDecimal.ZERO;
        record TenantChainKey(UUID tenantId, String chain) { }
        Map<TenantChainKey, Optional<String>> collectionAddresses = new HashMap<>();
        for (CollectionCandidateRecord candidate : candidates) {
            BigDecimal amount = assets.collectionAmount(profile, candidate, evmFeeReserve);
            if (amount.signum() <= 0) {
                continue;
            }
            TenantChainKey key = new TenantChainKey(candidate.getTenantId(), candidate.getChain());
            String hotAddress = collectionAddresses.computeIfAbsent(key,
                            ignored -> collectionPlanningService.destination(candidate.getTenantId(), candidate.getChain()))
                    .orElseThrow(() -> new IllegalStateException(
                            "active tenant collection/gas address is required for "
                                    + candidate.getChain() + " collection"));
            collectionRecordRepository.create(
                    candidate.getTenantId(),
                    candidate.getCustodyAddressId(),
                    collectionNo(candidate, amount),
                    candidate.getChain(),
                    candidate.getAssetSymbol(),
                    candidate.getAddress(),
                    hotAddress,
                    amount,
                    BigDecimal.ZERO,
                    null);
        }
    }
    private void processCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && evm7702ConfigRepository.existsManaged(profile.getChain(), profile.getNetwork())) {
            return;
        }
        ChainAddressRecord from = assets.requireAddress(record.getChain(), record.getAssetSymbol(), record.getFromAddress());
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (collectionRecordRepository.claimSigning(record.getTenantId(), record.getChain(), record.getCollectionNo(), null) != 1) {
                return;
            }
            String txHash = assets.isNative(profile, record.getAssetSymbol())
                    ? evmTransactionService.sendNative(record.getChain(), from, record.getToAddress(), record.getAmount())
                    : evmTransactionService.sendToken(
                            record.getChain(),
                            from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(),
                            record.getAmount());
            collectionRecordRepository.updateStatus(record.getTenantId(), record.getChain(), record.getCollectionNo(), "SENT", txHash, null, null);
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    solanaTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), assets.toAtomicDecimal(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                } else {
                    TokenDefinition token = assets.requireToken(record.getChain(), record.getAssetSymbol());
                    solanaTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            token.getContractAddress(), record.getToAddress(), record.getAmount());
                }
            }
            case "APTOS" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    aptosTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), assets.toAtomicDecimal(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                } else {
                    aptosTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()).getContractAddress(),
                            record.getToAddress(), assets.toAtomicDecimal(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                }
            }
            case "SUI" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    suiTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), assets.toAtomicDecimal(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                } else {
                    suiTransactionService.collectCoin(record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()).getContractAddress(),
                            record.getToAddress(), assets.toAtomicDecimal(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                }
            }
            case "TON" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    tonTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount(),
                            "collection:" + record.getCollectionNo());
                } else {
                    TokenDefinition token = assets.requireToken(record.getChain(), record.getAssetSymbol());
                    tonTransactionService.collectJetton(record.getTenantId(), record.getCollectionNo(), from,
                            token.getContractAddress(), record.getToAddress(), record.getAmount(),
                            "collection:" + record.getCollectionNo());
                }
            }
            case "XRP" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    xrpTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount());
                } else {
                    xrpTransactionService.collectIssuedCurrency(
                            record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "ADA" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    cardanoTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), assets.toAtomicBigInteger(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                } else {
                    cardanoTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "DOT" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    polkadotTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), assets.toAtomicBigInteger(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                } else {
                    polkadotTransactionService.collectAsset(record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "XMR" -> moneroTransactionService.collectNative(
                    record.getTenantId(), profile, record.getCollectionNo(), from,
                    record.getToAddress(), record.getAmount());
            case "NEAR" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    nearTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), assets.toAtomicBigInteger(record.getAmount(), assets.assetDecimals(record.getChain(), record.getAssetSymbol())));
                } else {
                    nearTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "HYPERCORE" -> {
                if (collectionRecordRepository.claimSigning(record.getTenantId(), record.getChain(), record.getCollectionNo(), null) != 1) {
                    return;
                }
                String actionId = assets.isNative(profile, record.getAssetSymbol())
                        ? hyperCoreTransactionService.sendUsd(profile, from, record.getToAddress(), record.getAmount())
                        : hyperCoreTransactionService.sendSpot(profile, from,
                        assets.requireToken(record.getChain(), record.getAssetSymbol()),
                        record.getToAddress(), record.getAmount());
                collectionRecordRepository.updateStatus(
                        record.getTenantId(),
                        record.getChain(),
                        record.getCollectionNo(),
                        "SENT",
                        actionId,
                        null,
                        null);
            }
            case "TRON" -> tronWorkflow.processCollection(profile, record, from);
            default -> {
            }
        }
    }
    private void confirmCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (evmBatchOwnershipService.collectionPending(record.getTenantId(), record.getId())) {
                return;
            }
            evmTransactionService.confirmCollection(
                    record.getTenantId(), record.getChain(), record.getCollectionNo());
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> solanaTransactionService.confirmCollection(
                    record.getTenantId(), record.getCollectionNo());
            case "APTOS" -> aptosTransactionService.confirmCollection(
                    record.getTenantId(), record.getCollectionNo());
            case "SUI" -> suiTransactionService.confirmCollection(
                    record.getTenantId(), record.getCollectionNo());
            case "TON" -> {
                ChainAddressRecord from = assets.requireAddress(record.getChain(), record.getAssetSymbol(), record.getFromAddress());
                tonTransactionService.confirmCollection(
                        record.getTenantId(), record.getCollectionNo(), tonOwnerAddress(from));
            }
            case "XRP" -> xrpTransactionService.confirmCollection(
                    record.getTenantId(), profile, record.getCollectionNo());
            case "ADA" -> cardanoTransactionService.confirmCollection(
                    record.getTenantId(), profile, record.getCollectionNo());
            case "DOT" -> polkadotTransactionService.confirmCollection(
                    record.getTenantId(), profile, record.getCollectionNo(), record.getAssetSymbol());
            case "XMR" -> moneroTransactionService.confirmCollection(
                    record.getTenantId(), profile, record.getCollectionNo());
            case "NEAR" -> nearTransactionService.confirmCollection(
                    record.getTenantId(), profile, record.getCollectionNo());
            case "HYPERCORE" -> hyperCoreTransactionService.confirmCollection(
                    record.getTenantId(), record.getCollectionNo(), record.getTxHash());
            case "TRON" -> tronWorkflow.confirmCollection(profile, record);
            default -> {
            }
        }
    }
    private String broadcastTonNative(WithdrawalOrderRecord order, ChainAddressRecord from) {
        TonTransactionService.PreparedTransfer prepared = tonTransactionService.prepareNative(
                from, order.getToAddress(), assets.toAtomicBigInteger(order.getAmount(), assets.assetDecimals(order.getChain(), order.getAssetSymbol())),
                "withdraw:" + order.getOrderNo());
        return tonTransactionService.broadcastAndRecord(prepared, from.getAddress(), order.getToAddress(),
                order.getAssetSymbol(), null, order.getAmount());
    }
    private String broadcastTonJetton(WithdrawalOrderRecord order, ChainAddressRecord from, TokenDefinition token) {
        ChainAddressRecord jettonWallet = chainAddressRepository.find(
                order.getChain(),
                token.getSymbol(),
                from.getUserId(),
                from.getBiz(),
                from.getAddressIndex(),
                from.getWalletRole())
                .orElseThrow(() -> new IllegalStateException("missing materialized TON Jetton wallet for "
                        + token.getSymbol() + " owner=" + from.getAddress()));
        TonTransactionService.PreparedTransfer prepared = tonTransactionService.prepareJetton(
                jettonWallet, jettonWallet.getAddress(), order.getToAddress(),
                assets.toAtomicBigInteger(order.getAmount(), token.getDecimals()),
                jettonWallet.getOwnerAddress(), "withdraw:" + order.getOrderNo());
        return tonTransactionService.broadcastAndRecord(prepared, jettonWallet.getOwnerAddress(),
                order.getToAddress(), token.getSymbol(), token.getContractAddress(), order.getAmount());
    }

    private void confirmTonWithdrawal(WithdrawalOrderRecord order) {
        if (order.getTxHash() == null || order.getTxHash().isBlank()) {
            return;
        }
        ChainAddressRecord from = assets.requireAddress(order.getTenantId(), order.getChain(),
                order.getAssetSymbol(), order.getFromAddress());
        if (tonTransactionService.confirmSentMessage(order.getTxHash(), tonOwnerAddress(from))) {
            settlements.confirm(order.getTenantId(), order.getChain(), order.getOrderNo(), order.getTxHash());
        }
    }
    private static String tonOwnerAddress(ChainAddressRecord address) {
        return address.getOwnerAddress() == null || address.getOwnerAddress().isBlank()
                ? address.getAddress() : address.getOwnerAddress();
    }

    private List<AccountChainProfile> enabledAccountProfiles() {
        return chainProfileRepository.listProfiles(true).stream()
                .filter(profile -> !"utxo".equalsIgnoreCase(profile.getFamily()))
                .filter(profile -> !"bitcoin-like".equalsIgnoreCase(profile.getFamily()))
                .sorted(Comparator
                        .comparingInt((AccountChainProfile profile) -> accountChainPriority(profile.getChain()))
                        .thenComparing(AccountChainProfile::getChain)
                        .thenComparing(AccountChainProfile::getNetwork))
                .toList();
    }
    private int accountChainPriority(String chain) {
        int index = ACCOUNT_CHAIN_PRIORITY.indexOf(chain);
        return index < 0 ? ACCOUNT_CHAIN_PRIORITY.size() : index;
    }
    private String collectionNo(CollectionCandidateRecord candidate, BigDecimal amount) {
        String basis = candidate.getChain() + "|" + candidate.getAssetSymbol() + "|"
                + candidate.getAccountId() + "|" + candidate.getAddress() + "|"
                + amount.stripTrailingZeros().toPlainString();
        return "COLL-" + candidate.getChain() + "-" + candidate.getAssetSymbol() + "-"
                + shortHash(basis);
    }
    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("missing SHA-256 digest", e);
        }
    }
}
