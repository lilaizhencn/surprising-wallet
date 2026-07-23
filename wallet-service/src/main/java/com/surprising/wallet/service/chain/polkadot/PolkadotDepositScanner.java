package com.surprising.wallet.service.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PolkadotDepositScanner {
    private static final String CHAIN = PolkadotRuntimeClient.CHAIN;
    private static final String SYMBOL = "DOT";
    private static final String NATIVE_SCANNER = "polkadot-runtime-scanner";
    private static final String ASSET_HUB_SCANNER = "polkadot-assethub-scanner";
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";
    private static final String WALLET_ROLE_CONTRACT_DEPLOYER = "CONTRACT_DEPLOYER";
    private static final int DEFAULT_DOT_DECIMALS = 10;

    private final PolkadotRuntimeClient runtimeClient;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "polkadot scanAndCredit");
        AccountChainProfile profile = profile();
        List<DepositEvent> events = new ArrayList<>();
        events.addAll(scanNative(profile));
        Map<String, TokenDefinition> tokens = tokensByAssetId();
        if (!tokens.isEmpty()) {
            events.addAll(scanAssets(profile, tokens));
        }
        return events;
    }

    private List<DepositEvent> scanNative(AccountChainProfile profile) {
        long latest = runtimeClient.latestFinalizedHeight();
        int requiredConfirmations = requiredConfirmations(profile);
        long safeHeight = Math.max(0L, latest - requiredConfirmations + 1L);
        long start = scanStart(profile, safeHeight, NATIVE_SCANNER);
        if (start > safeHeight) {
            repository.updateScanHeight(CHAIN, NATIVE_SCANNER, latest, safeHeight);
            return List.of();
        }
        long end = Math.min(safeHeight, start + scanBatch(profile) - 1L);
        Map<String, ChainAddressRecord> addresses = trackedDepositAddresses(SYMBOL);
        List<String> originalAddresses = addresses.values().stream()
                .map(ChainAddressRecord::getAddress)
                .distinct()
                .toList();
        List<DepositEvent> events = new ArrayList<>();
        List<PolkadotRuntimeClient.TransferEvent> transfers = runtimeClient.scanNativeTransfers(
                start, end, originalAddresses);
        if (!transfers.isEmpty()) {
            log.info("polkadot native scan found transfers count={} range={}-{}", transfers.size(), start, end);
        }
        for (PolkadotRuntimeClient.TransferEvent transfer : transfers) {
            DepositEvent event = toNativeDepositEvent(transfer, addresses, latest);
            if (event == null) {
                log.warn("polkadot native scan ignored transfer txHash={} to={} amountPlanck={}",
                        transfer.txHash(), transfer.toAddress(), transfer.amountPlanck());
                continue;
            }
            ChainAddressRecord tracked = addresses.get(normalize(event.toAddress()));
            repository.recordAndCreditDeposit(event, transfer.eventIndex(), requiredConfirmations,
                    tracked.getAccountId());
            events.add(event);
        }
        repository.updateScanHeight(CHAIN, NATIVE_SCANNER, latest, end);
        return events;
    }

    private List<DepositEvent> scanAssets(AccountChainProfile profile, Map<String, TokenDefinition> tokens) {
        long latest = runtimeClient.latestAssetHubFinalizedHeight();
        int requiredConfirmations = requiredConfirmations(profile);
        long safeHeight = Math.max(0L, latest - requiredConfirmations + 1L);
        long start = scanStart(profile, safeHeight, ASSET_HUB_SCANNER);
        if (start > safeHeight) {
            repository.updateScanHeight(CHAIN, ASSET_HUB_SCANNER, latest, safeHeight);
            return List.of();
        }
        long end = Math.min(safeHeight, start + scanBatch(profile) - 1L);
        Map<String, Map<String, ChainAddressRecord>> addressesBySymbol = trackedTokenDepositAddresses(tokens);
        List<String> addresses = addressesBySymbol.values().stream()
                .flatMap(addressBook -> addressBook.values().stream())
                .map(ChainAddressRecord::getAddress)
                .distinct()
                .toList();
        List<DepositEvent> events = new ArrayList<>();
        for (PolkadotRuntimeClient.TransferEvent transfer : runtimeClient.scanAssetTransfers(
                start, end, addresses, tokens)) {
            TokenDefinition token = tokens.get(PolkadotRuntimeClient.normalizeAssetId(transfer.assetId()));
            if (token == null) {
                continue;
            }
            Map<String, ChainAddressRecord> addressBook = addressesBySymbol.get(token.getSymbol());
            DepositEvent event = toAssetDepositEvent(transfer, addressBook, token, latest);
            if (event == null) {
                continue;
            }
            ChainAddressRecord tracked = addressBook.get(normalize(event.toAddress()));
            repository.recordAndCreditDeposit(event, transfer.eventIndex(), requiredConfirmations,
                    tracked.getAccountId());
            events.add(event);
        }
        repository.updateScanHeight(CHAIN, ASSET_HUB_SCANNER, latest, end);
        return events;
    }

    private DepositEvent toNativeDepositEvent(PolkadotRuntimeClient.TransferEvent transfer,
                                              Map<String, ChainAddressRecord> addresses,
                                              long latest) {
        ChainAddressRecord tracked = addresses.get(normalize(transfer.toAddress()));
        if (tracked == null || transfer.amountPlanck().signum() <= 0) {
            return null;
        }
        int confirmations = confirmations(latest, transfer.blockHeight());
        BigDecimal amount = fromAtomic(transfer.amountPlanck(), nativeDecimals());
        return new DepositEvent(ChainType.DOT, SYMBOL, transfer.txHash(),
                transfer.fromAddress(), tracked.getAddress(), amount, transfer.blockHeight(),
                transfer.txHash(), confirmations, null, transfer.rawPayload());
    }

    private DepositEvent toAssetDepositEvent(PolkadotRuntimeClient.TransferEvent transfer,
                                             Map<String, ChainAddressRecord> addresses,
                                             TokenDefinition token,
                                             long latest) {
        if (addresses == null) {
            return null;
        }
        ChainAddressRecord tracked = addresses.get(normalize(transfer.toAddress()));
        if (tracked == null || transfer.amountPlanck().signum() <= 0) {
            return null;
        }
        int confirmations = confirmations(latest, transfer.blockHeight());
        String assetId = PolkadotRuntimeClient.normalizeAssetId(transfer.assetId());
        BigDecimal amount = fromAtomic(transfer.amountPlanck(), token.getDecimals());
        return new DepositEvent(ChainType.DOT, token.getSymbol(), transfer.txHash(),
                transfer.fromAddress(), tracked.getAddress(), amount, transfer.blockHeight(),
                transfer.txHash(), confirmations, assetId, transfer.rawPayload());
    }

    private Map<String, ChainAddressRecord> trackedDepositAddresses(String assetSymbol) {
        Map<String, ChainAddressRecord> addresses = new HashMap<>();
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, assetSymbol)) {
            if (isTrackedRole(address)) {
                addresses.put(normalize(address.getAddress()), address);
            }
        }
        return addresses;
    }

    private boolean isTrackedRole(ChainAddressRecord address) {
        if (address == null) {
            return false;
        }
        String role = address.getWalletRole();
        if (WALLET_ROLE_DEPOSIT.equals(role)) {
            return true;
        }
        return SYMBOL.equalsIgnoreCase(address.getAssetSymbol())
                && WALLET_ROLE_CONTRACT_DEPLOYER.equals(role);
    }

    private Map<String, Map<String, ChainAddressRecord>> trackedTokenDepositAddresses(
            Map<String, TokenDefinition> tokens) {
        Map<String, Map<String, ChainAddressRecord>> addressesBySymbol = new HashMap<>();
        for (TokenDefinition token : tokens.values()) {
            addressesBySymbol.put(token.getSymbol(), trackedDepositAddresses(token.getSymbol()));
        }
        return addressesBySymbol;
    }

    private Map<String, TokenDefinition> tokensByAssetId() {
        Map<String, TokenDefinition> tokens = new HashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            String assetId = PolkadotRuntimeClient.normalizeAssetId(token.getContractAddress());
            if (Boolean.TRUE.equals(token.getActive()) && !assetId.isBlank()) {
                tokens.put(assetId, token);
            }
        }
        return tokens;
    }

    private long scanStart(AccountChainProfile profile, long safeHeight, String scannerName) {
        return repository.findScanSafeHeight(CHAIN, scannerName)
                .map(height -> Math.min(height + 1L, safeHeight + 1L))
                .orElseGet(() -> {
                    Long configured = profile.getScanStartHeight();
                    if (configured != null && configured > 0) {
                        return Math.min(configured, safeHeight + 1L);
                    }
                    return Math.max(0L, safeHeight - scanBatch(profile) + 1L);
                });
    }

    private static int scanBatch(AccountChainProfile profile) {
        Long maxBlocks = profile.getScanMaxBlocksPerRun();
        if (maxBlocks != null && maxBlocks > 0) {
            return Math.toIntExact(Math.min(maxBlocks, 100L));
        }
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 25 : Math.min(batchSize, 100);
    }

    private static int requiredConfirmations(AccountChainProfile profile) {
        Integer configured = profile.getDepositConfirmations();
        return configured == null || configured <= 0 ? 12 : configured;
    }

    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private int nativeDecimals() {
        return repository.findAsset(CHAIN, SYMBOL)
                .map(ChainAsset::getDecimals)
                .filter(decimals -> decimals != null && decimals > 0)
                .orElse(DEFAULT_DOT_DECIMALS);
    }

    private static int confirmations(long latest, long blockHeight) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, latest - blockHeight + 1L));
    }

    private static BigDecimal fromAtomic(BigInteger amount, int decimals) {
        return new BigDecimal(amount).movePointLeft(decimals).stripTrailingZeros();
    }

    private static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
}
