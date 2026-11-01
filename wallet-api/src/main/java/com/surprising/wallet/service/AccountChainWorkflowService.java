package com.surprising.wallet.service;

import com.surprising.wallet.common.signing.NearOperation;

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
 * 账户链提现、归集与确认用例；Monero 在同一串行任务内先执行扫描。
 * 各链的发送、领取和确认规则不同，入口显式委托对应交易服务。
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
        depositWorkflow.confirmMoneroObserved();
        moneroTransactionService.recoverObserved();
        AccountChainProfile profile = chainProfileRepository.findAccountByChain("XMR")
                .orElse(null);
        if (profile == null) {
            return;
        }
        processSingleAccountChain(profile);
    }

    /** 批量处理所有已启用账户链的待签名提现。 */
    public void processWithdrawals() {
        for (AccountChainProfile profile : runtimeConfigService.enabledAccountProfiles()) {
            processWithdrawals(profile);
        }
    }

    /** 批量确认所有已启用账户链的已发送提现。 */
    public void confirmWithdrawals() {
        for (AccountChainProfile profile : runtimeConfigService.enabledAccountProfiles()) {
            confirmWithdrawals(profile);
        }
    }

    /** 批量创建并签名所有已启用账户链的归集交易。 */
    public void processCollections() {
        for (AccountChainProfile profile : runtimeConfigService.enabledAccountProfiles()) {
            processCollections(profile);
        }
    }

    /** 批量确认所有已启用账户链的归集交易。 */
    public void confirmCollections() {
        for (AccountChainProfile profile : runtimeConfigService.enabledAccountProfiles()) {
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
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            withdrawalOrderRepository.markStaleSigningUnknown(profile.getChain(), Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : withdrawalOrderRepository.listByStatus(profile.getChain(),"BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new EvmSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),EvmSubmissionService.Kind.WITHDRAWAL);
                    if (!evmTransactionService.recover(operation,profile.getChain())) evmTransactionService.releaseUnsigned(operation,profile.getChain());
                }
                catch (RuntimeException error) { log.warn("EVM withdrawal recovery pending: {}",pending.getOrderNo()); }
            }
        }
        if ("TRON".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("TRON",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:withdrawalOrderRepository.listByStatus("TRON","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new TronSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),TronSubmissionService.Kind.WITHDRAWAL);
                    if(!tronWorkflow.recover(operation)) tronWorkflow.releaseUnsigned(operation);
                } catch(Exception error) {log.warn("TRON withdrawal recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("XRP".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("XRP",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:withdrawalOrderRepository.listByStatus("XRP","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new XrpSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),XrpSubmissionService.Kind.WITHDRAWAL);
                    if(!xrpTransactionService.recover(operation)) xrpTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("XRPL withdrawal recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("HYPERCORE".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("HYPERCORE",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:withdrawalOrderRepository.listByStatus("HYPERCORE","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new HyperCoreSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),HyperCoreSubmissionService.Kind.WITHDRAWAL);
                    if(!hyperCoreTransactionService.recover(operation)) hyperCoreTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("HyperCore withdrawal recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("SOLANA".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("SOLANA",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:withdrawalOrderRepository.listByStatus("SOLANA","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new SolanaSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),SolanaSubmissionService.Kind.WITHDRAWAL);
                    if(!solanaTransactionService.recover(operation)) solanaTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("Solana withdrawal recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("APTOS".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("APTOS",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:withdrawalOrderRepository.listByStatus("APTOS","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new AptosSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),AptosSubmissionService.Kind.WITHDRAWAL);
                    if(!aptosTransactionService.recover(operation)) aptosTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("Aptos withdrawal recovery pending: {}",pending.getOrderNo());}
            }
        }

        if ("SUI".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("SUI",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : withdrawalOrderRepository.listByStatus("SUI","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new SuiSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),SuiSubmissionService.Kind.WITHDRAWAL);
                    if (!suiTransactionService.recover(operation)) suiTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("Sui recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("NEAR".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("NEAR",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : withdrawalOrderRepository.listByStatus("NEAR","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new NearOperation(pending.getTenantId(),pending.getOrderNo(),NearOperation.Kind.WITHDRAWAL);
                    if (!nearTransactionService.recover(operation)) nearTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("NEAR recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("DOT".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("DOT",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : withdrawalOrderRepository.listByStatus("DOT","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new PolkadotSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),PolkadotSubmissionService.Kind.WITHDRAWAL);
                    if (!polkadotTransactionService.recover(operation)) polkadotTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("NEAR recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("ADA".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("ADA",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : withdrawalOrderRepository.listByStatus("ADA","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new CardanoSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),CardanoSubmissionService.Kind.WITHDRAWAL);
                    if (!cardanoTransactionService.recover(operation)) cardanoTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("Cardano recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("TON".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("TON",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : withdrawalOrderRepository.listByStatus("TON","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new TonSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),TonSubmissionService.Kind.WITHDRAWAL);
                    if (!tonTransactionService.recover(operation)) tonTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("TON recovery pending: {}",pending.getOrderNo());}
            }
        }
        if ("XMR".equals(profile.getChain())) {
            withdrawalOrderRepository.markStaleSigningUnknown("XMR",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:withdrawalOrderRepository.listByStatus("XMR","BROADCAST_UNKgetOrderNo()WN",CONFIRM_LIMIT)) {
                try {
                    var operation=new MoneroSubmissionService.Operation(pending.getTenantId(),pending.getOrderNo(),MoneroSubmissionService.Kind.WITHDRAWAL);
                    if(!moneroTransactionService.recover(operation))moneroTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error){log.warn("Monero original payment recovery pending: {}",pending.getOrderNo());}
            }
        }
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
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            collectionRecordRepository.markStaleSigningUnknown(profile.getChain(),Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : collectionRecordRepository.listByStatus(profile.getChain(),"BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new EvmSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),EvmSubmissionService.Kind.COLLECTION);
                    if (!evmTransactionService.recover(operation,profile.getChain())) evmTransactionService.releaseUnsigned(operation,profile.getChain());
                }
                catch (RuntimeException error) { log.warn("EVM collection recovery pending: {}",pending.getCollectionNo()); }
            }
        }
        if ("TRON".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("TRON",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:collectionRecordRepository.listByStatus("TRON","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new TronSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),TronSubmissionService.Kind.COLLECTION);
                    if(!tronWorkflow.recover(operation)) tronWorkflow.releaseUnsigned(operation);
                } catch(Exception error) {log.warn("TRON collection recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("XRP".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("XRP",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:collectionRecordRepository.listByStatus("XRP","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new XrpSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),XrpSubmissionService.Kind.COLLECTION);
                    if(!xrpTransactionService.recover(operation)) xrpTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("XRPL collection recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("HYPERCORE".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("HYPERCORE",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:collectionRecordRepository.listByStatus("HYPERCORE","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new HyperCoreSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),HyperCoreSubmissionService.Kind.COLLECTION);
                    if(!hyperCoreTransactionService.recover(operation)) hyperCoreTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("HyperCore collection recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("SOLANA".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("SOLANA",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:collectionRecordRepository.listByStatus("SOLANA","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new SolanaSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),SolanaSubmissionService.Kind.COLLECTION);
                    if(!solanaTransactionService.recover(operation)) solanaTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("Solana collection recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("APTOS".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("APTOS",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:collectionRecordRepository.listByStatus("APTOS","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation=new AptosSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),AptosSubmissionService.Kind.COLLECTION);
                    if(!aptosTransactionService.recover(operation)) aptosTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error) {log.warn("Aptos collection recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("SUI".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("SUI",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : collectionRecordRepository.listByStatus("SUI","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new SuiSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),SuiSubmissionService.Kind.COLLECTION);
                    if (!suiTransactionService.recover(operation)) suiTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("Sui recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("NEAR".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("NEAR",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : collectionRecordRepository.listByStatus("NEAR","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new NearOperation(pending.getTenantId(),pending.getCollectionNo(),NearOperation.Kind.COLLECTION);
                    if (!nearTransactionService.recover(operation)) nearTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("NEAR recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("DOT".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("DOT",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : collectionRecordRepository.listByStatus("DOT","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new PolkadotSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),PolkadotSubmissionService.Kind.COLLECTION);
                    if (!polkadotTransactionService.recover(operation)) polkadotTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("NEAR recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("ADA".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("ADA",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : collectionRecordRepository.listByStatus("ADA","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new CardanoSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),CardanoSubmissionService.Kind.COLLECTION);
                    if (!cardanoTransactionService.recover(operation)) cardanoTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("Cardano collection recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("TON".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("TON",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for (var pending : collectionRecordRepository.listByStatus("TON","BROADCAST_UNKNOWN",CONFIRM_LIMIT)) {
                try {
                    var operation = new TonSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),TonSubmissionService.Kind.COLLECTION);
                    if (!tonTransactionService.recover(operation)) tonTransactionService.releaseUnsigned(operation);
                } catch (RuntimeException error) {log.warn("TON recovery pending: {}",pending.getCollectionNo());}
            }
        }
        if ("XMR".equals(profile.getChain())) {
            collectionRecordRepository.markStaleSigningUnknown("XMR",Instant.now().minus(SIGNING_STALE_TIMEOUT));
            for(var pending:collectionRecordRepository.listByStatus("XMR","BROADCAST_UNKgetCollectionNo()WN",CONFIRM_LIMIT)) {
                try {
                    var operation=new MoneroSubmissionService.Operation(pending.getTenantId(),pending.getCollectionNo(),MoneroSubmissionService.Kind.COLLECTION);
                    if(!moneroTransactionService.recover(operation))moneroTransactionService.releaseUnsigned(operation);
                } catch(RuntimeException error){log.warn("Monero original payment recovery pending: {}",pending.getCollectionNo());}
            }
        }
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
                collectionRecordRepository.markSigningUnknown(record.getTenantId(),record.getChain(),record.getCollectionNo(),e.getClass().getSimpleName());
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
        int claimed = ("evm".equalsIgnoreCase(profile.getFamily()) || "TRON".equals(profile.getChain()) || "XRP".equals(profile.getChain()) || "HYPERCORE".equals(profile.getChain()) || "SOLANA".equals(profile.getChain()) || "APTOS".equals(profile.getChain()) || "TON".equals(profile.getChain()) || "SUI".equals(profile.getChain()) || "NEAR".equals(profile.getChain()) || "DOT".equals(profile.getChain()) || "ADA".equals(profile.getChain()) || "XMR".equals(profile.getChain()))
                ? withdrawalClaimService.claimDurable(tenantId,order.getChain(),order.getOrderNo(),from.getAddress())
                : withdrawalClaimService.claim(tenantId,order.getChain(),order.getOrderNo(),from.getAddress());
        if (claimed != 1) {
            return;
        }
        try {
            String txHash = dispatchWithdrawal(profile, order, from);
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("withdrawal broadcast returned empty tx hash");
            }
            if ("XMR".equals(profile.getChain())) return; // Native submission owns the durable original SENT transition.
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
                return evmTransactionService.sendNative(new EvmSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),EvmSubmissionService.Kind.WITHDRAWAL), chain, from, order.getToAddress(), order.getAmount());
            }
            TokenDefinition token = assets.requireToken(profile, order.getAssetSymbol());
            return evmTransactionService.sendToken(new EvmSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),EvmSubmissionService.Kind.WITHDRAWAL), chain, from, token, order.getToAddress(), order.getAmount());
        }
        return switch (chain) {
            case "SOLANA" -> solanaTransactionService.send(new SolanaSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),SolanaSubmissionService.Kind.WITHDRAWAL),from);
            case "APTOS" -> aptosTransactionService.send(new AptosSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),AptosSubmissionService.Kind.WITHDRAWAL),from);
            case "SUI" -> suiTransactionService.send(new SuiSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),SuiSubmissionService.Kind.WITHDRAWAL),from);
            case "TON" -> tonTransactionService.send(new TonSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),TonSubmissionService.Kind.WITHDRAWAL),from);
            case "XRP" -> assets.isNative(profile, order.getAssetSymbol())
                    ? xrpTransactionService.sendNative(new XrpSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),XrpSubmissionService.Kind.WITHDRAWAL),from, order.getToAddress(), order.getAmount())
                    : xrpTransactionService.sendIssuedCurrency(
                    new XrpSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),XrpSubmissionService.Kind.WITHDRAWAL),from, assets.requireToken(profile, order.getAssetSymbol()), order.getToAddress(), order.getAmount());
            case "ADA" -> cardanoTransactionService.send(new CardanoSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),CardanoSubmissionService.Kind.WITHDRAWAL),from);
            case "DOT" -> polkadotTransactionService.send(new PolkadotSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),PolkadotSubmissionService.Kind.WITHDRAWAL),from);
            case "XMR" -> {
                if (!assets.isNative(profile, order.getAssetSymbol())) {
                    throw new IllegalStateException("Monero tokens are not supported");
                }
                yield moneroTransactionService.send(new MoneroSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),MoneroSubmissionService.Kind.WITHDRAWAL),from);
            }
            case "NEAR" -> nearTransactionService.send(new NearOperation(order.getTenantId(),order.getOrderNo(),NearOperation.Kind.WITHDRAWAL),from);
            case "HYPERCORE" -> hyperCoreTransactionService.send(new HyperCoreSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),HyperCoreSubmissionService.Kind.WITHDRAWAL),from);
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
            case "TON" -> tonTransactionService.confirmWithdrawal(tenantId, order.getOrderNo());
            case "XRP" -> xrpTransactionService.confirmWithdrawal(tenantId, profile, order.getOrderNo());
            case "ADA" -> cardanoTransactionService.confirmWithdrawal(tenantId,order.getOrderNo());
            case "DOT" -> polkadotTransactionService.confirmWithdrawal(tenantId,order.getOrderNo());
            case "XMR" -> moneroTransactionService.confirmWithdrawal(tenantId,order.getOrderNo());
            case "NEAR" -> nearTransactionService.confirmWithdrawal(tenantId,order.getOrderNo());
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
                ? evmTransactionService.estimateCollectionFeeReserve(profile.getChain(),
                        tokenConfigRepository.listDefinitions(profile.getChain(), profile.getNetwork()).size())
                : BigDecimal.ZERO;
        BigDecimal nativeReserve = candidates.stream().anyMatch(candidate -> assets.isNative(profile, candidate.getAssetSymbol()))
                ? assets.collectionFeeReserve(profile, evmFeeReserve) : BigDecimal.ZERO;
        record TenantChainKey(UUID tenantId, String chain) { }
        Map<TenantChainKey, Optional<String>> collectionAddresses = new HashMap<>();
        for (CollectionCandidateRecord candidate : candidates) {
            BigDecimal amount = assets.collectionAmount(profile, candidate, nativeReserve);
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
            if (collectionRecordRepository.claimSigningDurable(record.getTenantId(), record.getChain(), record.getCollectionNo()) != 1) {
                return;
            }
            String txHash = assets.isNative(profile, record.getAssetSymbol())
                    ? evmTransactionService.sendNative(new EvmSubmissionService.Operation(record.getTenantId(),record.getCollectionNo(),EvmSubmissionService.Kind.COLLECTION), record.getChain(), from, record.getToAddress(), record.getAmount())
                    : evmTransactionService.sendToken(
                            new EvmSubmissionService.Operation(record.getTenantId(),record.getCollectionNo(),EvmSubmissionService.Kind.COLLECTION), record.getChain(),
                            from,
                            assets.requireToken(profile, record.getAssetSymbol()),
                            record.getToAddress(),
                            record.getAmount());
            collectionRecordRepository.updateStatus(record.getTenantId(), record.getChain(), record.getCollectionNo(), "SENT", txHash, null, null);
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> solanaTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "APTOS" -> aptosTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "SUI" -> suiTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "TON" -> tonTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "XRP" -> {
                if (assets.isNative(profile, record.getAssetSymbol())) {
                    xrpTransactionService.collectNative(record.getTenantId(), record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount());
                } else {
                    xrpTransactionService.collectIssuedCurrency(
                            record.getTenantId(), record.getCollectionNo(), from,
                            assets.requireToken(profile, record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "ADA" -> cardanoTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "DOT" -> polkadotTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "XMR" -> moneroTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "NEAR" -> nearTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
            case "HYPERCORE" -> hyperCoreTransactionService.collect(record.getTenantId(),record.getCollectionNo(),from);
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
            case "TON" -> tonTransactionService.confirmCollection(record.getTenantId(),record.getCollectionNo());
            case "XRP" -> xrpTransactionService.confirmCollection(
                    record.getTenantId(), profile, record.getCollectionNo());
            case "ADA" -> cardanoTransactionService.confirmCollection(record.getTenantId(),record.getCollectionNo());
            case "DOT" -> polkadotTransactionService.confirmCollection(record.getTenantId(),record.getCollectionNo());
            case "XMR" -> moneroTransactionService.confirmCollection(record.getTenantId(),record.getCollectionNo());
            case "NEAR" -> nearTransactionService.confirmCollection(record.getTenantId(),record.getCollectionNo());
            case "HYPERCORE" -> hyperCoreTransactionService.confirmCollection(
                    record.getTenantId(), record.getCollectionNo(), record.getTxHash());
            case "TRON" -> tronWorkflow.confirmCollection(profile, record);
            default -> {
            }
        }
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
