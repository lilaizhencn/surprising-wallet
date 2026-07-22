package com.surprising.wallet.jobs.account;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainCollectionRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TronTransactionRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.service.chain.aptos.AptosDepositScanner;
import com.surprising.wallet.service.chain.aptos.AptosTransactionService;
import com.surprising.wallet.service.chain.cardano.CardanoDepositScanner;
import com.surprising.wallet.service.chain.cardano.CardanoTransactionService;
import com.surprising.wallet.service.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.service.chain.evm.EvmDepositScanner;
import com.surprising.wallet.service.chain.hypercore.HyperCoreDepositScanner;
import com.surprising.wallet.service.chain.hypercore.HyperCoreTransactionService;
import com.surprising.wallet.service.chain.monero.MoneroDepositScanner;
import com.surprising.wallet.service.chain.monero.MoneroTransactionService;
import com.surprising.wallet.service.chain.near.NearDepositScanner;
import com.surprising.wallet.service.chain.near.NearTransactionService;
import com.surprising.wallet.service.chain.polkadot.PolkadotDepositScanner;
import com.surprising.wallet.service.chain.polkadot.PolkadotTransactionService;
import com.surprising.wallet.service.chain.solana.SolanaDepositScanner;
import com.surprising.wallet.service.chain.solana.SolanaTransactionService;
import com.surprising.wallet.service.chain.sui.SuiDepositScanner;
import com.surprising.wallet.service.chain.sui.SuiTransactionService;
import com.surprising.wallet.service.chain.ton.TonDepositScanner;
import com.surprising.wallet.service.chain.ton.TonTransactionService;
import com.surprising.wallet.service.chain.tron.TronAddressCodec;
import com.surprising.wallet.service.chain.tron.TronClientFactory;
import com.surprising.wallet.service.chain.tron.TronDepositScanner;
import com.surprising.wallet.service.chain.tron.TronScanner;
import com.surprising.wallet.service.chain.tron.TronTransactionService;
import com.surprising.wallet.service.chain.tron.TronTrc20Service;
import com.surprising.wallet.service.chain.tron.TronTridentClient;
import com.surprising.wallet.service.chain.tron.TronTridentKeyFactory;
import com.surprising.wallet.service.chain.xrp.XrpDepositScanner;
import com.surprising.wallet.service.chain.xrp.XrpTransactionService;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.ECKey;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountChainWorkflowService {
    private static final int WITHDRAW_LIMIT = 20;
    private static final int CONFIRM_LIMIT = 50;
    private static final int COLLECTION_LIMIT = 20;
    private static final Duration SIGNING_STALE_TIMEOUT = Duration.ofMinutes(10);
    private static final BigDecimal TRX_SUN = new BigDecimal("1000000");
    private static final List<String> ACCOUNT_CHAIN_PRIORITY = List.of(
            "XMR",
            "HYPERCORE",
            "ETH", "BASE", "BNB", "POLYGON", "ARBITRUM", "OPTIMISM", "AVAX_C", "HYPEREVM",
            "MANTLE", "LINEA", "SCROLL", "UNICHAIN",
            "SOLANA", "TRON", "XRP", "ADA", "TON", "APTOS", "SUI", "NEAR");

    private final ChainJdbcRepository repository;
    private final WalletRuntimeConfigService runtimeConfigService;
    private final HotWalletAddressService hotWalletAddressService;
    private final AccountSecp256k1KeyService secp256k1KeyService;

    private final EvmDepositScanner evmDepositScanner;
    private final EvmAccountTransactionService evmTransactionService;
    private final HyperCoreDepositScanner hyperCoreDepositScanner;
    private final HyperCoreTransactionService hyperCoreTransactionService;
    private final SolanaDepositScanner solanaDepositScanner;
    private final SolanaTransactionService solanaTransactionService;
    private final AptosDepositScanner aptosDepositScanner;
    private final AptosTransactionService aptosTransactionService;
    private final SuiDepositScanner suiDepositScanner;
    private final SuiTransactionService suiTransactionService;
    private final TonDepositScanner tonDepositScanner;
    private final TonTransactionService tonTransactionService;
    private final XrpDepositScanner xrpDepositScanner;
    private final XrpTransactionService xrpTransactionService;
    private final CardanoDepositScanner cardanoDepositScanner;
    private final CardanoTransactionService cardanoTransactionService;
    private final MoneroDepositScanner moneroDepositScanner;
    private final MoneroTransactionService moneroTransactionService;
    private final NearDepositScanner nearDepositScanner;
    private final NearTransactionService nearTransactionService;
    private final PolkadotDepositScanner polkadotDepositScanner;
    private final PolkadotTransactionService polkadotTransactionService;

    private final TronClientFactory tronClientFactory;
    private final TronDepositScanner tronDepositScanner;
    private final TronTransactionService tronTransactionService;
    private final TronTrc20Service tronTrc20Service;

    @Scheduled(cron = "11/30 * * * * ?")
    public void scanDepositsJob() {
        scanDeposits();
    }

    @Scheduled(cron = "13/30 * * * * ?")
    public void processWithdrawalsJob() {
        processWithdrawals();
    }

    @Scheduled(cron = "15/30 * * * * ?")
    public void confirmWithdrawalsJob() {
        confirmWithdrawals();
    }

    @Scheduled(cron = "17/30 * * * * ?")
    public void processCollectionsJob() {
        processCollections();
        confirmCollections();
    }

    @Scheduled(cron = "3/10 * * * * ?")
    public void moneroWorkflowJob() {
        AccountChainProfile profile = repository.findProfileByChain("XMR")
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                .orElse(null);
        if (profile == null) {
            return;
        }
        processSingleAccountChain(profile);
    }

    public void scanDeposits() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            scanDeposits(profile);
        }
    }

    public void processWithdrawals() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            processWithdrawals(profile);
        }
    }

    public void confirmWithdrawals() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            confirmWithdrawals(profile);
        }
    }

    public void processCollections() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            processCollections(profile);
        }
    }

    public void confirmCollections() {
        for (AccountChainProfile profile : enabledAccountProfiles()) {
            confirmCollections(profile);
        }
    }

    private void processSingleAccountChain(AccountChainProfile profile) {
        scanDeposits(profile);
        processWithdrawals(profile);
        confirmWithdrawals(profile);
        processCollections(profile);
        confirmCollections(profile);
    }

    private void scanDeposits(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_SCAN)) {
            return;
        }
        try {
            switch (profile.getChain()) {
                case "SOLANA" -> scanSolana();
                case "APTOS" -> scanAptos();
                case "SUI" -> scanSui();
                case "TON" -> scanTon();
                case "XRP" -> scanXrp();
                case "ADA" -> scanCardano();
                case "DOT" -> scanPolkadot();
                case "NEAR" -> scanNear();
                case "XMR" -> scanMonero(profile);
                case "HYPERCORE" -> scanHyperCore(profile);
                case "TRON" -> scanTron(profile);
                default -> {
                    if ("evm".equalsIgnoreCase(profile.getFamily())) {
                        scanEvm(profile);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("account-chain deposit scan failed: chain={} error={}",
                    profile.getChain(), e.getMessage(), e);
        }
    }

    private void processWithdrawals(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_WITHDRAW)) {
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

    private void processCollections(AccountChainProfile profile) {
        if (!runtimeConfigService.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_COLLECTION)) {
            return;
        }
        createCollectionCandidates(profile);
        for (ChainCollectionRecord record : repository.listCollectionsForSigning(
                profile.getChain(), COLLECTION_LIMIT)) {
            try {
                processCollection(profile, record);
            } catch (Exception e) {
                repository.updateCollectionStatus(record.getChain(), record.getCollectionNo(),
                        "FAILED", null, e.getMessage(), null);
                log.warn("account-chain collection failed: chain={} collectionNo={} error={}",
                        record.getChain(), record.getCollectionNo(), e.getMessage(), e);
            }
        }
    }

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

    private void processWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order) {
        ChainAddressRecord from = requireAddress(order.getChain(), order.getAssetSymbol(), order.getFromAddress());
        if (repository.claimWithdrawalSigning(order.getChain(), order.getOrderNo(), from.getAddress()) != 1) {
            return;
        }
        try {
            String txHash = dispatchWithdrawal(profile, order, from);
            if (txHash == null || txHash.isBlank()) {
                throw new IllegalStateException("withdrawal broadcast returned empty tx hash");
            }
            if (repository.markWithdrawalSent(order.getChain(), order.getOrderNo(), from.getAddress(), txHash) != 1) {
                throw new IllegalStateException("withdrawal state changed before SENT: " + order.getOrderNo());
            }
        } catch (Exception e) {
            repository.markWithdrawalBroadcastUnknown(order.getChain(), order.getOrderNo(),
                    from.getAddress(), e.getMessage());
            throw new IllegalStateException(e);
        }
    }

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
            case "TRON" -> broadcastTron(profile, order, from);
            default -> throw new IllegalStateException("unsupported account-chain withdrawal: " + chain);
        };
    }

    private void confirmWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order) throws Exception {
        ChainAddressRecord from = requireAddress(order.getChain(), order.getAssetSymbol(), order.getFromAddress());
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            evmTransactionService.confirmWithdrawal(order.getChain(), order.getOrderNo(),
                    order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> solanaTransactionService.confirmWithdrawal(
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "APTOS" -> aptosTransactionService.confirmWithdrawal(
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "SUI" -> suiTransactionService.confirmWithdrawal(
                    order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "TON" -> confirmTonWithdrawal(order, from);
            case "XRP" -> xrpTransactionService.confirmWithdrawal(
                    profile, order.getOrderNo(), order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
            case "ADA" -> cardanoTransactionService.confirmWithdrawal(
                    profile, order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "DOT" -> polkadotTransactionService.confirmWithdrawal(
                    profile, order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "XMR" -> moneroTransactionService.confirmWithdrawal(
                    profile, order.getOrderNo(), order.getTxHash(), debitAccountId(order, from),
                    withdrawalDebitAmount(order), order.getToAddress(), order.getAmount());
            case "NEAR" -> nearTransactionService.confirmWithdrawal(
                    profile, order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "HYPERCORE" -> hyperCoreTransactionService.confirmWithdrawal(
                    order.getOrderNo(), order.getTxHash(), order.getAssetSymbol(),
                    debitAccountId(order, from), withdrawalDebitAmount(order));
            case "TRON" -> confirmTronWithdrawal(profile, order, from);
            default -> {
            }
        }
    }

    private void createCollectionCandidates(AccountChainProfile profile) {
        for (CollectionCandidateRecord candidate : repository.listCollectableLedgerBalances(
                profile.getChain(), BigDecimal.ZERO, COLLECTION_LIMIT)) {
            BigDecimal amount = collectionAmount(profile, candidate);
            if (amount.signum() <= 0) {
                continue;
            }
            String hotAddress = "evm".equalsIgnoreCase(profile.getFamily())
                    && !isNative(profile, candidate.getAssetSymbol())
                    ? repository.findActiveTenantCollectionAddress(
                                    candidate.getTenantId(), candidate.getChain())
                            .orElseThrow(() -> new IllegalStateException(
                                    "active tenant collection/gas address is required for "
                                            + candidate.getChain() + " token collection"))
                    : hotAddress(profile, candidate.getAssetSymbol());
            repository.createCollectionRecord(candidate.getTenantId(), candidate.getCustodyAddressId(),
                    collectionNo(candidate, amount), candidate.getChain(),
                    candidate.getAssetSymbol(), candidate.getAddress(), hotAddress,
                    amount, BigDecimal.ZERO, null);
        }
    }

    private void processCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && !isNative(profile, record.getAssetSymbol())
                && repository.isEvm7702CollectionActive(profile.getChain(), profile.getNetwork())) {
            return;
        }
        ChainAddressRecord from = requireAddress(record.getChain(), record.getAssetSymbol(), record.getFromAddress());
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            if (repository.claimCollectionSigning(record.getChain(), record.getCollectionNo(), null) != 1) {
                return;
            }
            String txHash = isNative(profile, record.getAssetSymbol())
                    ? evmTransactionService.sendNative(record.getChain(), from, record.getToAddress(), record.getAmount())
                    : evmTransactionService.sendToken(record.getChain(), from, requireToken(record.getChain(),
                    record.getAssetSymbol()), record.getToAddress(), record.getAmount());
            repository.updateCollectionStatus(record.getChain(), record.getCollectionNo(), "SENT", txHash, null, null);
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    solanaTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                } else {
                    TokenDefinition token = requireToken(record.getChain(), record.getAssetSymbol());
                    solanaTransactionService.collectToken(record.getCollectionNo(), from,
                            token.getContractAddress(), record.getToAddress(), record.getAmount());
                }
            }
            case "APTOS" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    aptosTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                } else {
                    aptosTransactionService.collectToken(record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()).getContractAddress(),
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                }
            }
            case "SUI" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    suiTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                } else {
                    suiTransactionService.collectCoin(record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()).getContractAddress(),
                            record.getToAddress(), toAtomicDecimal(record.getAmount(), assetDecimals(record)));
                }
            }
            case "TON" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    tonTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount(),
                            "collection:" + record.getCollectionNo());
                } else {
                    TokenDefinition token = requireToken(record.getChain(), record.getAssetSymbol());
                    tonTransactionService.collectJetton(record.getCollectionNo(), from,
                            token.getContractAddress(), record.getToAddress(), record.getAmount(),
                            "collection:" + record.getCollectionNo());
                }
            }
            case "XRP" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    xrpTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), record.getAmount());
                } else {
                    xrpTransactionService.collectIssuedCurrency(record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "ADA" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    cardanoTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicBigInteger(record.getAmount(), assetDecimals(record)));
                } else {
                    cardanoTransactionService.collectToken(record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "DOT" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    polkadotTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicBigInteger(record.getAmount(), assetDecimals(record)));
                } else {
                    polkadotTransactionService.collectAsset(record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "XMR" -> moneroTransactionService.collectNative(profile, record.getCollectionNo(), from,
                    record.getToAddress(), record.getAmount());
            case "NEAR" -> {
                if (isNative(profile, record.getAssetSymbol())) {
                    nearTransactionService.collectNative(record.getCollectionNo(), from,
                            record.getToAddress(), toAtomicBigInteger(record.getAmount(), assetDecimals(record)));
                } else {
                    nearTransactionService.collectToken(record.getCollectionNo(), from,
                            requireToken(record.getChain(), record.getAssetSymbol()),
                            record.getToAddress(), record.getAmount());
                }
            }
            case "HYPERCORE" -> {
                if (repository.claimCollectionSigning(record.getChain(), record.getCollectionNo(), null) != 1) {
                    return;
                }
                String actionId = isNative(profile, record.getAssetSymbol())
                        ? hyperCoreTransactionService.sendUsd(profile, from, record.getToAddress(), record.getAmount())
                        : hyperCoreTransactionService.sendSpot(profile, from,
                        requireToken(record.getChain(), record.getAssetSymbol()),
                        record.getToAddress(), record.getAmount());
                repository.updateCollectionStatus(record.getChain(), record.getCollectionNo(),
                        "SENT", actionId, null, null);
            }
            case "TRON" -> processTronCollection(profile, record, from);
            default -> {
            }
        }
    }

    private void confirmCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            evmTransactionService.confirmCollection(record.getChain(), record.getCollectionNo());
            return;
        }
        switch (profile.getChain()) {
            case "SOLANA" -> solanaTransactionService.confirmCollection(record.getCollectionNo());
            case "APTOS" -> aptosTransactionService.confirmCollection(record.getCollectionNo());
            case "SUI" -> suiTransactionService.confirmCollection(record.getCollectionNo());
            case "TON" -> {
                ChainAddressRecord from = requireAddress(
                        record.getChain(), record.getAssetSymbol(), record.getFromAddress());
                tonTransactionService.confirmCollection(record.getCollectionNo(), tonOwnerAddress(from));
            }
            case "XRP" -> xrpTransactionService.confirmCollection(profile, record.getCollectionNo());
            case "ADA" -> cardanoTransactionService.confirmCollection(profile, record.getCollectionNo());
            case "DOT" -> polkadotTransactionService.confirmCollection(
                    profile, record.getCollectionNo(), record.getAssetSymbol());
            case "XMR" -> moneroTransactionService.confirmCollection(profile, record.getCollectionNo());
            case "NEAR" -> nearTransactionService.confirmCollection(profile, record.getCollectionNo());
            case "HYPERCORE" -> hyperCoreTransactionService.confirmCollection(
                    record.getCollectionNo(), record.getTxHash());
            case "TRON" -> confirmTronCollection(profile, record);
            default -> {
            }
        }
    }

    private void scanEvm(AccountChainProfile profile) throws IOException {
        ChainType chainType = ChainType.valueOf(profile.getChain());
        long latest = evmDepositScanner.getLatestBlockNumber(chainType).longValueExact();
        long start = scanStart(profile, latest, "native-evm", "erc20-evm");
        long end = Math.min(latest, start + scanBatch(profile) - 1L);
        for (long height = start; height <= end; height++) {
            evmDepositScanner.scanAndCreditNative(chainType, height);
            evmDepositScanner.scanAndCreditErc20(chainType, height);
        }
    }

    private void scanSolana() {
        solanaDepositScanner.scanAndCredit();
    }

    private void scanAptos() {
        aptosDepositScanner.scanAndCredit();
    }

    private void scanSui() {
        suiDepositScanner.scanAndCredit();
    }

    private void scanTon() {
        tonDepositScanner.scanAndCredit();
    }

    private void scanXrp() {
        xrpDepositScanner.scanAndCredit();
    }

    private void scanCardano() {
        cardanoDepositScanner.scanAndCredit();
    }

    private void scanPolkadot() {
        polkadotDepositScanner.scanAndCredit();
    }

    private void scanNear() {
        nearDepositScanner.scanAndCredit();
    }

    private void scanHyperCore(AccountChainProfile profile) {
        hyperCoreDepositScanner.scanAndCredit(profile);
    }

    private void scanMonero(AccountChainProfile profile) {
        moneroDepositScanner.scanAndCredit(profile);
    }

    private void scanTron(AccountChainProfile profile) throws Exception {
        try (TronTridentClient client = tronClientFactory.create()) {
            long latest = client.getNowBlock().getBlockHeader().getRawData().getNumber();
            long start = scanStart(profile, latest, "TRON_TRX", "TRON_TRC20");
            long end = Math.min(latest, start + scanBatch(profile) - 1L);
            Set<String> addresses = repository.listEnabledChainScanAddresses("TRON");
            Map<String, TronScanner.TokenConfig> tokens = tronTokens();
            for (long height = start; height <= end; height++) {
                tronDepositScanner.scanAndCreditTrx(client, height, addresses, profile.getDepositConfirmations());
                tronDepositScanner.scanAndCreditTrc20(client, height, tokens, addresses,
                        profile.getDepositConfirmations());
            }
        }
    }

    private String broadcastTonNative(WithdrawalOrderRecord order, ChainAddressRecord from) {
        TonTransactionService.PreparedTransfer prepared = tonTransactionService.prepareNative(
                from, order.getToAddress(), toAtomicBigInteger(order.getAmount(), assetDecimals(order)),
                "withdraw:" + order.getOrderNo());
        return tonTransactionService.broadcastAndRecord(prepared, from.getAddress(), order.getToAddress(),
                order.getAssetSymbol(), null, order.getAmount());
    }

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

    private String broadcastTron(AccountChainProfile profile, WithdrawalOrderRecord order,
                                 ChainAddressRecord from) throws Exception {
        try (TronTridentClient client = tronClientFactory.create()) {
            KeyPair keyPair = tronKey(profile, from);
            if (isNative(profile, order.getAssetSymbol())) {
                long amountSun = order.getAmount().multiply(TRX_SUN).longValueExact();
                TronTransactionService.SignedTronTransaction signed = tronTransactionService
                        .signTrxTransfer(client, keyPair, order.getToAddress(), amountSun);
                tronTransactionService.broadcast(client, signed);
                recordTronSent(order.getChain(), signed.txId(), from.getAddress(), order.getToAddress(),
                        "TRX", null, order.getAmount());
                return signed.txId();
            }
            TokenDefinition token = requireToken(order.getChain(), order.getAssetSymbol());
            TronTransactionService.SignedTronTransaction signed = tronTrc20Service.signTransfer(
                    client, keyPair, token.getContractAddress(), order.getToAddress(),
                    order.getAmount(), token.getDecimals(), tronFeeLimitSun(profile));
            tronTrc20Service.broadcast(client, signed);
            recordTronSent(order.getChain(), signed.txId(), from.getAddress(), order.getToAddress(),
                    token.getSymbol(), token.getContractAddress(), order.getAmount());
            return signed.txId();
        }
    }

    private void processTronCollection(AccountChainProfile profile, ChainCollectionRecord record,
                                       ChainAddressRecord from) throws Exception {
        if (repository.claimCollectionSigning(record.getChain(), record.getCollectionNo(), null) != 1) {
            return;
        }
        try (TronTridentClient client = tronClientFactory.create()) {
            KeyPair keyPair = tronKey(profile, from);
            String txHash;
            if (isNative(profile, record.getAssetSymbol())) {
                long amountSun = record.getAmount().multiply(TRX_SUN).longValueExact();
                TronTransactionService.SignedTronTransaction signed = tronTransactionService
                        .signTrxTransfer(client, keyPair, record.getToAddress(), amountSun);
                tronTransactionService.broadcast(client, signed);
                txHash = signed.txId();
                recordTronSent(record.getChain(), txHash, from.getAddress(), record.getToAddress(),
                        "TRX", null, record.getAmount());
            } else {
                TokenDefinition token = requireToken(record.getChain(), record.getAssetSymbol());
                TronTransactionService.SignedTronTransaction signed = tronTrc20Service.signTransfer(
                        client, keyPair, token.getContractAddress(), record.getToAddress(),
                        record.getAmount(), token.getDecimals(), tronFeeLimitSun(profile));
                tronTrc20Service.broadcast(client, signed);
                txHash = signed.txId();
                recordTronSent(record.getChain(), txHash, from.getAddress(), record.getToAddress(),
                        token.getSymbol(), token.getContractAddress(), record.getAmount());
            }
            repository.updateCollectionStatus(record.getChain(), record.getCollectionNo(), "SENT", txHash, null, null);
        } catch (Exception e) {
            repository.updateCollectionStatus(record.getChain(), record.getCollectionNo(),
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    private void confirmTronWithdrawal(AccountChainProfile profile, WithdrawalOrderRecord order,
                                       ChainAddressRecord from) throws Exception {
        Response.TransactionInfo txInfo = confirmedTronInfo(profile, order.getTxHash());
        if (txInfo != null) {
            recordTronConfirmed(order.getChain(), order.getTxHash(), from.getAddress(),
                    order.getToAddress(), order.getAssetSymbol(), order.getAmount(), txInfo);
            repository.confirmWithdrawalAndSettle(order.getChain(), order.getOrderNo(), order.getTxHash(),
                    order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
        }
    }

    private void confirmTronCollection(AccountChainProfile profile, ChainCollectionRecord record) throws Exception {
        Response.TransactionInfo txInfo = confirmedTronInfo(profile, record.getTxHash());
        if (txInfo != null) {
            recordTronConfirmed(record.getChain(), record.getTxHash(), record.getFromAddress(),
                    record.getToAddress(), record.getAssetSymbol(), record.getAmount(), txInfo);
            repository.markCollectionConfirmed(record.getChain(), record.getCollectionNo(), record.getTxHash());
        }
    }

    private Response.TransactionInfo confirmedTronInfo(
            AccountChainProfile profile, String txHash) throws Exception {
        if (txHash == null || txHash.isBlank()) {
            return null;
        }
        try (TronTridentClient client = tronClientFactory.create()) {
            Response.TransactionInfo txInfo = client.getTransactionInfo(txHash, NodeType.SOLIDITY_NODE);
            if (txInfo == null || txInfo.getBlockNumber() <= 0) {
                return null;
            }
            long best = client.getNowBlock().getBlockHeader().getRawData().getNumber();
            long confirmations = Math.max(0, best - txInfo.getBlockNumber() + 1);
            return confirmations >= Math.max(1, profile.getWithdrawConfirmations())
                    ? txInfo : null;
        }
    }

    private void recordTronConfirmed(
            String chain, String txHash, String from, String to, String symbol,
            BigDecimal amount, Response.TransactionInfo txInfo) {
        repository.recordTronTransaction(TronTransactionRecord.builder()
                .chain(chain)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .amount(amount)
                .fee(BigDecimal.valueOf(txInfo.getFee()).movePointLeft(6))
                .blockHeight(txInfo.getBlockNumber())
                .confirmations(1)
                .status("CONFIRMED")
                .rawPayload(txInfo.toString())
                .build());
    }

    private void confirmTonWithdrawal(WithdrawalOrderRecord order, ChainAddressRecord from) {
        if (order.getTxHash() == null || order.getTxHash().isBlank()) {
            return;
        }
        if (tonTransactionService.confirmSentMessage(order.getTxHash(), tonOwnerAddress(from))) {
            repository.confirmWithdrawalAndSettle(order.getChain(), order.getOrderNo(), order.getTxHash(),
                    order.getAssetSymbol(), debitAccountId(order, from), withdrawalDebitAmount(order));
        }
    }

    private static String tonOwnerAddress(ChainAddressRecord address) {
        return address.getOwnerAddress() == null || address.getOwnerAddress().isBlank()
                ? address.getAddress() : address.getOwnerAddress();
    }

    private void recordTronSent(String chain, String txHash, String from, String to,
                                String symbol, String contract, BigDecimal amount) {
        repository.recordTronTransaction(TronTransactionRecord.builder()
                .chain(chain)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .contractAddress(contract)
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .confirmations(0)
                .status("SENT")
                .build());
    }

    private BigDecimal collectionAmount(AccountChainProfile profile, CollectionCandidateRecord candidate) {
        BigDecimal amount = candidate.getAmount() == null ? BigDecimal.ZERO : candidate.getAmount();
        if (!isNative(profile, candidate.getAssetSymbol())) {
            return amount;
        }
        if ("XRP".equals(profile.getChain())) {
            return xrpTransactionService.collectableNativeAmount(candidate.getAddress(), amount);
        }
        BigDecimal reserve = nativeCollectionFeeReserve(profile, candidate);
        return amount.subtract(reserve).max(BigDecimal.ZERO);
    }

    private BigDecimal nativeCollectionFeeReserve(AccountChainProfile profile, CollectionCandidateRecord candidate) {
        int decimals = assetDecimals(candidate.getChain(), candidate.getAssetSymbol());
        BigDecimal configured = profile.getDefaultFee() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(profile.getDefaultFee()).movePointLeft(decimals);
        BigDecimal feeReserve;
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            feeReserve = configured.max(new BigDecimal("0.0001"));
        } else {
            feeReserve = switch (profile.getChain()) {
                case "SOLANA" -> configured.max(new BigDecimal("0.00002"));
                case "TON" -> configured.max(new BigDecimal("0.02"));
                case "XRP" -> configured.max(new BigDecimal("0.000012"));
                case "ADA" -> configured.max(new BigDecimal("0.3"));
                case "DOT" -> configured.max(new BigDecimal("0.02"));
                case "XMR" -> configured.max(new BigDecimal("0.003"));
                case "NEAR" -> configured.max(new BigDecimal("3"));
                case "HYPERCORE" -> BigDecimal.ZERO;
                case "SUI" -> configured.max(new BigDecimal("0.02"));
                case "APTOS" -> configured.max(new BigDecimal("0.05"));
                case "TRON" -> configured.max(new BigDecimal("1"));
                default -> configured;
            };
        }
        BigDecimal dustReserve = profile.getDustThreshold() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(profile.getDustThreshold()).movePointLeft(decimals);
        return feeReserve.add(dustReserve);
    }

    private String hotAddress(AccountChainProfile profile, String assetSymbol) {
        ChainAddressRecord hot = hotWalletAddressService.findDefaultHotAddress(profile.getChain(), assetSymbol)
                .orElseGet(() -> hotWalletAddressService.requireVerifiedDefaultHotAddress(profile));
        return hot.getAddress();
    }

    private ChainAddressRecord requireAddress(String chain, String symbol, String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("missing source address");
        }
        return repository.findChainAddressByAddress(chain, symbol, address)
                .or(() -> repository.findChainAddressByAddress(chain, address))
                .orElseThrow(() -> new IllegalStateException(
                        "missing chain_address for " + chain + "/" + symbol + " " + address));
    }

    private TokenDefinition requireToken(String chain, String symbol) {
        return repository.findToken(chain, symbol)
                .orElseThrow(() -> new IllegalStateException("missing token_config for " + chain + "/" + symbol));
    }

    private int assetDecimals(WithdrawalOrderRecord order) {
        return assetDecimals(order.getChain(), order.getAssetSymbol());
    }

    private int assetDecimals(ChainCollectionRecord record) {
        return assetDecimals(record.getChain(), record.getAssetSymbol());
    }

    private int assetDecimals(String chain, String symbol) {
        return repository.findAsset(chain, symbol)
                .map(ChainAsset::getDecimals)
                .orElseGet(() -> requireToken(chain, symbol).getDecimals());
    }

    private BigDecimal toAtomicDecimal(BigDecimal amount, int decimals) {
        return new BigDecimal(toAtomicBigInteger(amount, decimals));
    }

    private BigInteger toAtomicBigInteger(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private long toAtomicLong(BigDecimal amount, int decimals) {
        return toAtomicBigInteger(amount, decimals).longValueExact();
    }

    private boolean isNative(AccountChainProfile profile, String symbol) {
        return symbol != null && symbol.equalsIgnoreCase(profile.getNativeSymbol());
    }

    private String debitAccountId(WithdrawalOrderRecord order, ChainAddressRecord from) {
        String debitAccountId = order.getDebitAccountId();
        return debitAccountId == null || debitAccountId.isBlank() ? from.getAccountId() : debitAccountId;
    }

    private BigDecimal withdrawalDebitAmount(WithdrawalOrderRecord order) {
        BigDecimal amount = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
        BigDecimal fee = order.getFee() == null ? BigDecimal.ZERO : order.getFee();
        return amount.add(fee);
    }

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

    private int accountChainPriority(String chain) {
        int index = ACCOUNT_CHAIN_PRIORITY.indexOf(chain);
        return index < 0 ? ACCOUNT_CHAIN_PRIORITY.size() : index;
    }

    private long scanStart(AccountChainProfile profile, long latest, String... scannerNames) {
        long configured = profile.getScanStartHeight() == null ? 0L : profile.getScanStartHeight();
        long fallback = configured > 0 ? configured : Math.max(0L, latest - scanBatch(profile) + 1L);
        long next = Long.MAX_VALUE;
        for (String scannerName : scannerNames) {
            long candidate = repository.findScanSafeHeight(profile.getChain(), scannerName)
                    .map(height -> height + 1L)
                    .orElse(fallback);
            next = Math.min(next, candidate);
        }
        return Math.min(next == Long.MAX_VALUE ? fallback : next, latest);
    }

    private long scanBatch(AccountChainProfile profile) {
        if (profile.getScanMaxBlocksPerRun() != null && profile.getScanMaxBlocksPerRun() > 0) {
            return profile.getScanMaxBlocksPerRun();
        }
        if (profile.getScanBatchSize() != null && profile.getScanBatchSize() > 0) {
            return profile.getScanBatchSize();
        }
        return 20L;
    }

    private Map<String, TronScanner.TokenConfig> tronTokens() {
        Map<String, TronScanner.TokenConfig> tokens = new LinkedHashMap<>();
        for (TokenDefinition token : repository.listTokens("TRON")) {
            String contract = token.getContractAddress();
            if (contract == null || contract.isBlank()) {
                continue;
            }
            String hex = contract.startsWith("T")
                    ? TronAddressCodec.base58ToHex(contract)
                    : TronAddressCodec.normalizeHexAddress(contract);
            tokens.put(hex.toLowerCase(Locale.ROOT),
                    new TronScanner.TokenConfig(token.getSymbol(), hex, token.getDecimals()));
        }
        return tokens;
    }

    private KeyPair tronKey(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = secp256k1KeyService.key(profile, from);
        return TronTridentKeyFactory.fromBitcoinEcKey(ecKey);
    }

    private long tronFeeLimitSun(AccountChainProfile profile) {
        Long configured = profile.getDefaultFee();
        return configured == null || configured <= 0 ? 30_000_000L : Math.max(10_000_000L, configured);
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
