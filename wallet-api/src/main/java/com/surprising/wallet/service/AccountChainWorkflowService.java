package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.chain.aptos.AptosTransactionService;
import com.surprising.wallet.chain.cardano.CardanoTransactionService;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.hypercore.HyperCoreTransactionService;
import com.surprising.wallet.chain.monero.MoneroTransactionService;
import com.surprising.wallet.chain.near.NearTransactionService;
import com.surprising.wallet.chain.polkadot.PolkadotTransactionService;
import com.surprising.wallet.chain.solana.SolanaTransactionService;
import com.surprising.wallet.chain.sui.SuiTransactionService;
import com.surprising.wallet.chain.starknet.StarknetTransactionService;
import com.surprising.wallet.chain.ton.TonTransactionService;
import com.surprising.wallet.chain.xrp.XrpTransactionService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 * @see ChainJdbcRepository
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
            "MANTLE", "LINEA", "SCROLL", "UNICHAIN", "ZKSYNC", "BERACHAIN", "GNOSIS", "CELO", "MONAD",
            "WORLD_CHAIN", "INK", "TAIKO", "SONEIUM", "MODE", "LISK", "KATANA", "MEGAETH",
            "X_LAYER", "DEGEN", "ROBINHOOD_CHAIN", "OKT_CHAIN", "ETHERLINK", "IOTA_EVM", "OASIS_EMERALD", "CRONOS", "SONIC",
            "PULSECHAIN", "ZETACHAIN", "CORE", "SOMNIA", "RONIN", "CHILIZ", "IOTEX", "KAIA", "PLASMA", "STORY", "SEI", "CONFLUX", "VECTOR_SMART_CHAIN", "KROWN",
            "STARKNET", "SOLANA", "TRON", "XRP", "ADA", "TON", "APTOS", "SUI", "NEAR");

    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code runtimeConfigService}，用于保存运行配置和策略参数。
     */
    private final WalletRuntimeConfigService runtimeConfigService;
    /**
     * 保存 {@code depositWorkflow}，用于承载当前对象的运行配置或业务数据。
     */
    private final AccountChainDepositWorkflow depositWorkflow;
    /**
     * 保存 {@code assets}，表示链、网络、资产或代币配置。
     */
    private final AccountChainAssetService assets;
    /**
     * 保存 {@code tronWorkflow}，用于承载当前对象的运行配置或业务数据。
     */
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
    /** Starknet 链交易服务。 */
    private final StarknetTransactionService starknetTransactionService;

    /**
     * Monero 专用工作流：扫描 -> 提现 -> 确认提现 -> 归集 -> 确认归集。
     */
    public void moneroWorkflow() {
        AccountChainProfile profile = repository.findProfileByChain("XMR")
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
            scanDeposits(profile);
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
            scanDeposits(profile);
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
    /**
     * 执行或处理 {@code processSingleAccountChain} 对应的业务流程，并维护状态和异常边界。
     */
    private void processSingleAccountChain(AccountChainProfile profile) {
        scanDeposits(profile);
        processWithdrawals(profile);
        confirmWithdrawals(profile);
        processCollections(profile);
        confirmCollections(profile);
    }
    /**
     * 扫描或观察 {@code scanDeposits} 对应的链上状态，并转换为业务可用结果。
     */
    private void scanDeposits(AccountChainProfile profile) {
        depositWorkflow.scan(profile);
    }
    /**
     * 执行或处理 {@code processWithdrawals} 对应的业务流程，并维护状态和异常边界。
     */
    private void processWithdrawals(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_WITHDRAW)) {
            return;
        }
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && repository.isEvm7702BatchWithdrawalManaged(
                        profile.getChain(), profile.getNetwork())) {
            return;
        }
        int stale = repository.markStaleSigningWithdrawalsUnknown(
                profile.getChain(), Instant.now().minus(SIGNING_STALE_TIMEOUT));
        if (stale > 0) {
            log.warn("marked stale signing withdrawals as broadcast-unknown: chain={} count={}",
                    profile.getChain(), stale);
        }
        for (WithdrawalOrderRecord order : repository.listWithdrawalsForSigning(profile.getChain(), WITHDRAW_LIMIT)) {
            try {
                processWithdrawal(profile, order);
            } catch (Exception e) {
                log.warn("account-chain withdrawal failed: chain={} orderNo={} error={}",
                        order.getChain(), order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
    /**
     * 处理 {@code confirmWithdrawals} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private void confirmWithdrawals(AccountChainProfile profile) {
        for (WithdrawalOrderRecord order : repository.listWithdrawalsByStatus(
                profile.getChain(), "SENT", CONFIRM_LIMIT)) {
            try {
                confirmWithdrawal(profile, order);
            } catch (Exception e) {
                log.warn("account-chain withdrawal confirmation failed: chain={} orderNo={} error={}",
                        order.getChain(), order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
    /**
     * 执行或处理 {@code processCollections} 对应的业务流程，并维护状态和异常边界。
     */
    private void processCollections(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_COLLECTION)) {
            return;
        }
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && repository.isEvm7702Managed(profile.getChain(), profile.getNetwork())
                && !repository.isEvm7702CollectionActive(profile.getChain(), profile.getNetwork())) {
            return;
        }
        createCollectionCandidates(profile);
        for (ChainCollectionRecord record : repository.listCollectionsForSigning(
                profile.getChain(), COLLECTION_LIMIT)) {
            try {
                processCollection(profile, record);
            } catch (Exception e) {
                repository.updateCollectionStatus(record.getTenantId(), record.getChain(), record.getCollectionNo(),
                        "FAILED", null, e.getMessage(), null);
                log.warn("account-chain collection failed: chain={} collectionNo={} error={}",
                        record.getChain(), record.getCollectionNo(), e.getMessage(), e);
            }
        }
    }
    /**
     * 处理 {@code confirmCollections} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private void confirmCollections(AccountChainProfile profile) {
        for (ChainCollectionRecord record : repository.listCollectionsByStatus(
                profile.getChain(), "SENT", CONFIRM_LIMIT)) {
            try {
                confirmCollection(profile, record);
            } catch (Exception e) {
                log.warn("account-chain collection confirmation failed: chain={} collectionNo={} error={}",
                        record.getChain(), record.getCollectionNo(), e.getMessage(), e);
            }
        }
    }
    /**
     * 执行或处理 {@code processWithdrawal} 对应的业务流程，并维护状态和异常边界。
     */
    private void processWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order) {
        UUID tenantId = Objects.requireNonNull(order.getTenantId(), "withdrawal tenantId is required");
        ChainAddressRecord from = requireAddress(
                tenantId, order.getChain(), order.getAssetSymbol(), order.getFromAddress());
        if (repository.claimWithdrawalSigning(
                tenantId, order.getChain(), order.getOrderNo(), from.getAddress()) != 1) {
            return;
        }
        try {
            String txHash = dispatchWithdrawal(profile, order, from);
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("withdrawal broadcast returned empty tx hash");
            }
            if (repository.markWithdrawalSent(
                    tenantId, order.getChain(), order.getOrderNo(), from.getAddress(), txHash) != 1) {
                throw new IllegalStateException("withdrawal state changed before SENT: " + order.getOrderNo());
            }
        } catch (Exception e) {
            repository.markWithdrawalBroadcastUnknown(
                    tenantId, order.getChain(), order.getOrderNo(), from.getAddress(), e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    /**
     * 执行或处理 {@code dispatchWithdrawal} 对应的业务流程，并维护状态和异常边界。
     */
    private String dispatchWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order,
                                      ChainAddressRecord from) throws Exception {
        String chain = profile.getChain();
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (isNative(profile, order.getAssetSymbol())) {
                return evmTransactionService.sendNative(chain, from, order.getToAddress(), order.getAmount());
            }
            TokenDefinition token = requireToken(chain, order.getAssetSymbol());
            return evmTransactionService.sendToken(chain, from, token, order.getToAddress(), order.getAmount());
        }
        return switch (chain) {
            case "SOLANA" -> {
                if (isNative(profile, order.getAssetSymbol())) {
                    yield solanaTransactionService.sendNative(
                            from, order.getToAddress(), toAtomicLong(order.getAmount(), assetDecimals(order)));
                }
                TokenDefinition token = requireToken(chain, order.getAssetSymbol());
                yield solanaTransactionService.sendTokenAmount(
                        from, token.getContractAddress(), order.getToAddress(), order.getAmount(), token.getDecimals());
            }
            case "APTOS" -> isNative(profile, order.getAssetSymbol())
                    ? aptosTransactionService.sendNative(from, order.getToAddress(),
                    toAtomicLong(order.getAmount(), assetDecimals(order)))
                    : aptosTransactionService.sendToken(from, requireToken(chain, order.getAssetSymbol()),
                    order.getToAddress(), toAtomicLong(order.getAmount(), assetDecimals(order)));
            case "SUI" -> isNative(profile, order.getAssetSymbol())
                    ? suiTransactionService.sendNative(from, order.getToAddress(),
                    toAtomicLong(order.getAmount(), assetDecimals(order)))
                    : suiTransactionService.sendCoin(from, requireToken(chain, order.getAssetSymbol()).getContractAddress(),
                    order.getToAddress(), toAtomicLong(order.getAmount(), assetDecimals(order)));
            case "TON" -> isNative(profile, order.getAssetSymbol())
                    ? broadcastTonNative(order, from)
                    : broadcastTonJetton(order, from, requireToken(chain, order.getAssetSymbol()));
            case "XRP" -> isNative(profile, order.getAssetSymbol())
                    ? xrpTransactionService.sendNative(from, order.getToAddress(), order.getAmount())
                    : xrpTransactionService.sendIssuedCurrency(
                    from, requireToken(chain, order.getAssetSymbol()), order.getToAddress(), order.getAmount());
            case "ADA" -> {
                if (isNative(profile, order.getAssetSymbol())) {
                    yield cardanoTransactionService.sendNative(from, order.getToAddress(),
                            toAtomicBigInteger(order.getAmount(), assetDecimals(order)));
                }
                yield cardanoTransactionService.sendToken(from, requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "DOT" -> {
                if (isNative(profile, order.getAssetSymbol())) {
                    yield polkadotTransactionService.sendNative(from, order.getToAddress(),
                            toAtomicBigInteger(order.getAmount(), assetDecimals(order)));
                }
                yield polkadotTransactionService.sendAsset(from, requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "XMR" -> {
                if (!isNative(profile, order.getAssetSymbol())) {
                    throw new IllegalStateException("Monero tokens are not supported");
                }
                yield moneroTransactionService.sendNative(profile, from, order.getToAddress(), order.getAmount());
            }
            case "NEAR" -> {
                if (isNative(profile, order.getAssetSymbol())) {
                    yield nearTransactionService.sendNative(from, order.getToAddress(),
                            toAtomicBigInteger(order.getAmount(), assetDecimals(order)));
                }
                yield nearTransactionService.sendToken(from, requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "HYPERCORE" -> {
                if (isNative(profile, order.getAssetSymbol())) {
                    yield hyperCoreTransactionService.sendUsd(profile, from, order.getToAddress(), order.getAmount());
                }
                yield hyperCoreTransactionService.sendSpot(profile, from, requireToken(chain, order.getAssetSymbol()),
                        order.getToAddress(), order.getAmount());
            }
            case "TRON" -> tronWorkflow.broadcast(profile, order, from);
            case "STARKNET" -> isNative(profile, order.getAssetSymbol())
                    ? starknetTransactionService.sendNative(profile, from, order.getToAddress(), order.getAmount())
                    : starknetTransactionService.sendToken(profile, from, requireToken(chain, order.getAssetSymbol()),
                    order.getToAddress(), order.getAmount());
            default -> throw new IllegalStateException("unsupported account-chain withdrawal: " + chain);
        };
    }
    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private void confirmWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order) throws Exception {
        UUID tenantId = Objects.requireNonNull(order.getTenantId(), "withdrawal tenantId is required");
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && repository.isWithdrawalInPendingEvm7702Batch(tenantId, order.getId())) {
            return;
        }
        ChainAddressRecord from = requireAddress(
                tenantId, order.getChain(), order.getAssetSymbol(), order.getFromAddress());
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            evmTransactionService.confirmWithdrawal(tenantId, order.getChain(), order.getOrderNo(),
                    order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> solanaTransactionService.confirmWithdrawal(tenantId,
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "APTOS" -> aptosTransactionService.confirmWithdrawal(tenantId,
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "SUI" -> suiTransactionService.confirmWithdrawal(tenantId,
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "TON" -> confirmTonWithdrawal(order, from);
            case "XRP" -> xrpTransactionService.confirmWithdrawal(tenantId,
                    profile, order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "ADA" -> cardanoTransactionService.confirmWithdrawal(tenantId,
                    profile, order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "DOT" -> polkadotTransactionService.confirmWithdrawal(tenantId,
                    profile, order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "XMR" -> moneroTransactionService.confirmWithdrawal(tenantId,
                    profile, order.getOrderNo(), order.getTxHash(), debitAccountId(order, from),
                    withdrawalDebitAmount(order), order.getToAddress(), order.getAmount());
            case "NEAR" -> nearTransactionService.confirmWithdrawal(tenantId,
                    profile, order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "HYPERCORE" -> hyperCoreTransactionService.confirmWithdrawal(tenantId,
                    order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "TRON" -> tronWorkflow.confirmWithdrawal(profile, order, from);
            case "STARKNET" -> starknetTransactionService.confirmWithdrawal(tenantId, profile,
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from),
                    withdrawalDebitAmount(order));
            default -> {
            }
        }
    }
    /**
     * 构建或生成 {@code createCollectionCandidates} 对应的结果，并执行输入和状态校验。
     */
    private void createCollectionCandidates(AccountChainProfile profile) {
        List<CollectionCandidateRecord> candidates = repository.listCollectableLedgerBalances(
                profile.getChain(), BigDecimal.ZERO, COLLECTION_LIMIT);
        BigDecimal evmFeeReserve = "evm".equalsIgnoreCase(profile.getFamily())
                ? evmTransactionService.estimateCollectionFeeReserve(
                        profile.getChain(), repository.listTokens(profile.getChain()).size())
                : BigDecimal.ZERO;
        BigDecimal starknetFeeReserve = "STARKNET".equalsIgnoreCase(profile.getChain())
                ? starknetTransactionService.estimateCollectionFeeReserve(profile, repository.listTokens(profile.getChain()).size())
                : BigDecimal.ZERO;
        for (CollectionCandidateRecord candidate : candidates) {
            BigDecimal amount = collectionAmount(profile, candidate, evmFeeReserve.add(starknetFeeReserve));
            if (amount.signum() <= 0) {
                continue;
            }
            String hotAddress = repository.findActiveTenantCollectionAddress(
                            candidate.getTenantId(), candidate.getChain())
                    .orElseThrow(() -> new IllegalStateException(
                            "active tenant collection/gas address is required for "
                                    + candidate.getChain() + " collection"));
            repository.createCollectionRecord(candidate.getTenantId(), candidate.getCustodyAddressId(),
                    collectionNo(candidate, amount), candidate.getChain(),
                    candidate.getAssetSymbol(), candidate.getAddress(), hotAddress,
                    amount, BigDecimal.ZERO, null);
        }
    }
    /**
     * 执行或处理 {@code processCollection} 对应的业务流程，并维护状态和异常边界。
     */
    private void processCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && repository.isEvm7702Managed(profile.getChain(), profile.getNetwork())) {
            return;
        }
        ChainAddressRecord from = requireAddress(record.getChain(), record.getAssetSymbol(), record.getFromAddress());
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (repository.claimCollectionSigning(
                    record.getTenantId(), record.getChain(), record.getCollectionNo(), null) != 1) {
                return;
            }
            String txHash = isNative(profile, record.getAssetSymbol())
                    ? evmTransactionService.sendNative(record.getChain(), from, record.getToAddress(), record.getAmount())
                    : evmTransactionService.sendToken(record.getChain(), from, requireToken(record.getChain(),
                    record.getAssetSymbol()), record.getToAddress(), record.getAmount());
            repository.updateCollectionStatus(record.getTenantId(), record.getChain(),
                    record.getCollectionNo(), "SENT", txHash, null, null);
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    solanaTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                } else {
                    TokenDefinition token = requireToken(record.getChain(), record.getAssetSymbol());
                    solanaTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            token.getContractAddress(), record.getToAddress(), record.getAmount());
                }
            }
            case "APTOS" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    aptosTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                } else {
                    aptosTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()).getContractAddress(),
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                }
            }
            case "SUI" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    suiTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                } else {
                    suiTransactionService.collectCoin(record.getTenantId(), record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()).getContractAddress(),
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                }
            }
            case "TON" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    tonTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount(),
                            "collection:" + record.getCollectionNo());
                } else {
                    TokenDefinition token = requireToken(record.getChain(), record.getAssetSymbol());
                    tonTransactionService.collectJetton(record.getTenantId(), record.getCollectionNo(), from,
                            token.getContractAddress(), record.getToAddress(), record.getAmount(),
                            "collection:" + record.getCollectionNo());
                }
            }
            case "XRP" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    xrpTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount());
                } else {
                    xrpTransactionService.collectIssuedCurrency(
                            record.getTenantId(), record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "ADA" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    cardanoTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicBigInteger(record.getAmount(), assetDecimals(record)));
                } else {
                    cardanoTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "DOT" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    polkadotTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicBigInteger(record.getAmount(), assetDecimals(record)));
                } else {
                    polkadotTransactionService.collectAsset(record.getTenantId(), record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "XMR" -> moneroTransactionService.collectNative(
                    record.getTenantId(), profile, record.getCollectionNo(), from,
                    record.getToAddress(), record.getAmount());
            case "NEAR" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    nearTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicBigInteger(record.getAmount(), assetDecimals(record)));
                } else {
                    nearTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "HYPERCORE" -> {
                if (repository.claimCollectionSigning(
                        record.getTenantId(), record.getChain(), record.getCollectionNo(), null) != 1) {
                    return;
                }
                String actionId = isNative(profile, record.getAssetSymbol())
                        ? hyperCoreTransactionService.sendUsd(profile, from, record.getToAddress(), record.getAmount())
                        : hyperCoreTransactionService.sendSpot(profile, from,
                        requireToken(record.getChain(), record.getAssetSymbol()),
                        record.getToAddress(), record.getAmount());
                repository.updateCollectionStatus(record.getTenantId(), record.getChain(), record.getCollectionNo(),
                        "SENT", actionId, null, null);
            }
            case "TRON" -> tronWorkflow.processCollection(profile, record, from);
            case "STARKNET" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    starknetTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), profile,
                            from, record.getToAddress(), record.getAmount());
                } else {
                    starknetTransactionService.collectToken(record.getTenantId(), record.getCollectionNo(), profile,
                            from, requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            default -> {
            }
        }
    }
    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private void confirmCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (repository.isCollectionInPendingEvm7702Batch(
                    record.getTenantId(), record.getId())) {
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
                ChainAddressRecord from = requireAddress(
                        record.getChain(), record.getAssetSymbol(), record.getFromAddress());
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
            case "STARKNET" -> starknetTransactionService.confirmCollection(record.getTenantId(), profile,
                    record.getCollectionNo(), record.getAssetSymbol());
            default -> {
            }
        }
    }
    /**
     * 发送或广播 {@code broadcastTonNative} 对应的链上请求，并返回节点处理结果。
     */
    private String broadcastTonNative(WithdrawalOrderRecord order, ChainAddressRecord from) {
        TonTransactionService.PreparedTransfer prepared = tonTransactionService.prepareNative(
                from, order.getToAddress(), toAtomicBigInteger(order.getAmount(), assetDecimals(order)),
                "withdraw:" + order.getOrderNo());
        return tonTransactionService.broadcastAndRecord(prepared, from.getAddress(), order.getToAddress(),
                order.getAssetSymbol(), null, order.getAmount());
    }
    /**
     * 发送或广播 {@code broadcastTonJetton} 对应的链上请求，并返回节点处理结果。
     */
    private String broadcastTonJetton(WithdrawalOrderRecord order, ChainAddressRecord from, TokenDefinition token) {
        ChainAddressRecord jettonWallet = repository.findChainAddress(
                        order.getChain(), token.getSymbol(), from.getUserId(), from.getBiz(),
                        from.getAddressIndex(), from.getWalletRole())
                .orElseThrow(() -> new IllegalStateException("missing materialized TON Jetton wallet for "
                        + token.getSymbol() + " owner=" + from.getAddress()));
        TonTransactionService.PreparedTransfer prepared = tonTransactionService.prepareJetton(
                jettonWallet, jettonWallet.getAddress(), order.getToAddress(),
                toAtomicBigInteger(order.getAmount(), token.getDecimals()),
                jettonWallet.getOwnerAddress(), "withdraw:" + order.getOrderNo());
        return tonTransactionService.broadcastAndRecord(prepared, jettonWallet.getOwnerAddress(),
                order.getToAddress(), token.getSymbol(), token.getContractAddress(), order.getAmount());
    }

    /**
     * 处理 {@code confirmTonWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private void confirmTonWithdrawal(WithdrawalOrderRecord order, ChainAddressRecord from) {
        if (order.getTxHash() == null || order.getTxHash().isBlank()) {
            return;
        }
        if (tonTransactionService.confirmSentMessage(order.getTxHash(), tonOwnerAddress(from))) {
            repository.confirmWithdrawalAndSettle(order.getTenantId(), order.getChain(), order.getOrderNo(), order.getTxHash(),
                    order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
        }
    }
    /**
     * 编码 {@code tonOwnerAddress} 对应的数据，生成链上或接口所需的表示。
     */
    private static String tonOwnerAddress(ChainAddressRecord address) {
        return address.getOwnerAddress() == null || address.getOwnerAddress().isBlank()
                ? address.getAddress() : address.getOwnerAddress();
    }

    /**
     * 处理 {@code collectionAmount} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private BigDecimal collectionAmount(AccountChainProfile profile, CollectionCandidateRecord candidate,
                                        BigDecimal evmFeeReserve) {
        return assets.collectionAmount(profile, candidate, evmFeeReserve);
    }
    /**
     * 校验 {@code requireAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    private ChainAddressRecord requireAddress(String chain, String symbol, String address) {
        return assets.requireAddress(chain, symbol, address);
    }
    /**
     * 校验 {@code requireAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    private ChainAddressRecord requireAddress(UUID tenantId, String chain, String symbol, String address) {
        return assets.requireAddress(tenantId, chain, symbol, address);
    }
    /**
     * 校验 {@code requireToken} 对应的前置条件，不满足时抛出明确异常。
     */
    private TokenDefinition requireToken(String chain, String symbol) {
        return assets.requireToken(chain, symbol);
    }
    /**
     * 获取或查询 {@code assetDecimals} 对应的数据，并向调用方返回当前业务状态。
     */
    private int assetDecimals(WithdrawalOrderRecord order) {
        return assetDecimals(order.getChain(), order.getAssetSymbol());
    }
    /**
     * 获取或查询 {@code assetDecimals} 对应的数据，并向调用方返回当前业务状态。
     */
    private int assetDecimals(ChainCollectionRecord record) {
        return assetDecimals(record.getChain(), record.getAssetSymbol());
    }
    /**
     * 获取或查询 {@code assetDecimals} 对应的数据，并向调用方返回当前业务状态。
     */
    private int assetDecimals(String chain, String symbol) {
        return assets.assetDecimals(chain, symbol);
    }
    /**
     * 编码 {@code toAtomicDecimal} 对应的数据，生成链上或接口所需的表示。
     */
    private BigDecimal toAtomicDecimal(BigDecimal amount, int decimals) {
        return assets.toAtomicDecimal(amount, decimals);
    }
    /**
     * 编码 {@code toAtomicBigInteger} 对应的数据，生成链上或接口所需的表示。
     */
    private BigInteger toAtomicBigInteger(BigDecimal amount, int decimals) {
        return assets.toAtomicBigInteger(amount, decimals);
    }
    /**
     * 编码 {@code toAtomicLong} 对应的数据，生成链上或接口所需的表示。
     */
    private long toAtomicLong(BigDecimal amount, int decimals) {
        return assets.toAtomicLong(amount, decimals);
    }
    /**
     * 判断 {@code isNative} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isNative(AccountChainProfile profile, String symbol) {
        return assets.isNative(profile, symbol);
    }
    /**
     * 执行 {@code debitAccountId} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String debitAccountId(WithdrawalOrderRecord order, ChainAddressRecord from) {
        return assets.debitAccountId(order, from);
    }
    /**
     * 处理 {@code withdrawalDebitAmount} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private BigDecimal withdrawalDebitAmount(WithdrawalOrderRecord order) {
        return assets.withdrawalDebitAmount(order);
    }
    /**
     * 写入或更新 {@code enabledAccountProfiles} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private List<AccountChainProfile> enabledAccountProfiles() {
        return repository.listEnabledChainProfiles().stream()
                .filter(profile -> !"utxo".equalsIgnoreCase(profile.getFamily()))
                .filter(profile -> !"bitcoin-like".equalsIgnoreCase(profile.getFamily()))
                .sorted(Comparator
                        .comparingInt((AccountChainProfile profile) -> accountChainPriority(profile.getChain()))
                        .thenComparing(AccountChainProfile::getChain)
                        .thenComparing(AccountChainProfile::getNetwork))
                .toList();
    }
    /**
     * 执行 {@code accountChainPriority} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private int accountChainPriority(String chain) {
        int index = ACCOUNT_CHAIN_PRIORITY.indexOf(chain);
        return index < 0 ? ACCOUNT_CHAIN_PRIORITY.size() : index;
    }
    /**
     * 处理 {@code collectionNo} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private String collectionNo(CollectionCandidateRecord candidate, BigDecimal amount) {
        String basis = candidate.getChain() + "|" + candidate.getAssetSymbol() + "|"
                + candidate.getAccountId() + "|" + candidate.getAddress() + "|"
                + amount.stripTrailingZeros().toPlainString();
        return "COLL-" + candidate.getChain() + "-" + candidate.getAssetSymbol() + "-"
                + shortHash(basis);
    }
    /**
     * 执行 {@code shortHash} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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
