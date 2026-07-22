package com.surprising.wallet.service.chain.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.SuiTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SuiDepositScanner {
    private static final String CHAIN = "SUI";
    static final String SCANNER = "sui-balance-change-scanner";

    private final SuiRpcClient rpc;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "sui scanAndCredit");
        AccountChainProfile profile = profile();
        long latest = rpc.latestCheckpoint();
        long start = scanStart(profile, latest);
        long end = Math.min(latest, start + scanBatchSize(profile) - 1L);
        List<DepositEvent> events = new ArrayList<>();
        List<AssetScanTarget> targets = new ArrayList<>();
        Set<String> platformAddresses = platformAddresses();
        addTargets(targets, "SUI", SuiRpcClient.SUI_COIN_TYPE);
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            if (token.getContractAddress() != null && Boolean.TRUE.equals(token.getActive())) {
                addTargets(targets, token.getSymbol(), token.getContractAddress());
            }
        }
        for (JsonNode transaction : rpc.checkpointTransactions(start, end)) {
            for (AssetScanTarget target : targets) {
                scanTransaction(transaction, target.symbol(), target.coinType(), target.address(),
                        profile, latest, platformAddresses, events);
            }
        }
        repository.updateScanHeight(CHAIN, SCANNER, latest, end);
        return events;
    }

    private void addTargets(List<AssetScanTarget> targets, String symbol, String coinType) {
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, symbol)) {
            if ("DEPOSIT".equals(address.getWalletRole())) {
                targets.add(new AssetScanTarget(symbol, coinType, address));
            }
        }
    }

    private void scanTransaction(JsonNode transaction, String symbol, String coinType,
                                 ChainAddressRecord address, AccountChainProfile profile,
                                 long bestCheckpoint, Set<String> platformAddresses,
                                 List<DepositEvent> events) {
        String sender = SuiHex.normalizeAddress(transaction.path("transaction").path("data").path("sender")
                .asText("0x0"));
        if (platformAddresses.contains(sender)) {
            return;
        }
        String digest = transaction.path("digest").asText();
        long checkpoint = transaction.path("checkpoint").asLong(bestCheckpoint);
        int confirmations = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1, bestCheckpoint - checkpoint + 1));
        JsonNode balanceChanges = transaction.path("balanceChanges");
        for (int index = 0; index < balanceChanges.size(); index++) {
            JsonNode change = balanceChanges.get(index);
            String changeCoinType = change.path("coinType").asText();
            if (!sameCoinType(coinType, changeCoinType)) {
                continue;
            }
            if (!ownerMatches(change.path("owner"), address.getAddress())) {
                continue;
            }
            BigDecimal rawAmount = new BigDecimal(change.path("amount").asText("0"));
            if (rawAmount.signum() <= 0) {
                continue;
            }
            BigDecimal amount = rawAmount.movePointLeft(decimals(symbol));
            DepositEvent event = new DepositEvent(ChainType.SUI, symbol, digest, sender,
                    address.getAddress(), amount, checkpoint, digest, confirmations,
                    SuiRpcClient.SUI_COIN_TYPE.equals(coinType) ? null : coinType,
                    transaction.toString());
            repository.recordSuiTransaction(SuiTransactionRecord.builder()
                    .chain(CHAIN)
                    .txDigest(digest)
                    .sender(sender)
                    .receiver(address.getAddress())
                    .assetSymbol(symbol)
                    .coinType(coinType)
                    .amount(amount)
                    .gasUsed(totalGas(transaction.path("effects").path("gasUsed")))
                    .checkpoint(checkpoint)
                    .status(confirmations >= profile.getDepositConfirmations() ? "CONFIRMED" : "CONFIRMING")
                    .rawPayload(transaction.toString())
                    .build());
            repository.recordAndCreditDeposit(event, index, profile.getDepositConfirmations(),
                    address.getAccountId());
            events.add(event);
        }
    }

    private boolean ownerMatches(JsonNode owner, String address) {
        String expected = SuiHex.normalizeAddress(address);
        if (owner.isTextual()) {
            return expected.equals(SuiHex.normalizeAddress(owner.asText()));
        }
        String value = owner.path("AddressOwner").asText(null);
        return value != null && expected.equals(SuiHex.normalizeAddress(value));
    }

    private Set<String> platformAddresses() {
        Set<String> addresses = new HashSet<>();
        for (ChainAddressRecord tracked : repository.listChainAddresses(CHAIN)) {
            addAddress(addresses, tracked.getAddress());
            addAddress(addresses, tracked.getOwnerAddress());
        }
        return addresses;
    }

    private void addAddress(Set<String> addresses, String address) {
        if (address == null || address.isBlank()) {
            return;
        }
        try {
            addresses.add(SuiHex.normalizeAddress(address));
        } catch (RuntimeException ignored) {
            // Ignore malformed historical records; address creation validates new rows.
        }
    }

    private boolean sameCoinType(String expected, String actual) {
        return normalizeCoinType(expected).equals(normalizeCoinType(actual));
    }

    private String normalizeCoinType(String value) {
        String[] parts = value.split("::");
        if (parts.length != 3) {
            return value;
        }
        return SuiHex.normalizeAddress(parts[0]) + "::" + parts[1] + "::" + parts[2];
    }

    private int decimals(String symbol) {
        return repository.findAsset(CHAIN, symbol)
                .map(asset -> asset.getDecimals())
                .orElseGet(() -> repository.findToken(CHAIN, symbol)
                        .map(TokenDefinition::getDecimals)
                        .orElse(9));
    }

    private long totalGas(JsonNode gas) {
        return gas.path("computationCost").asLong(0)
                + gas.path("storageCost").asLong(0)
                - gas.path("storageRebate").asLong(0);
    }

    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private long scanStart(AccountChainProfile profile, long latest) {
        return repository.findScanSafeHeight(CHAIN, SCANNER)
                .map(height -> Math.min(latest, height + 1L))
                .orElseGet(() -> {
                    long configured = profile.getScanStartHeight() == null
                            ? 0L : Math.max(0L, profile.getScanStartHeight());
                    return Math.max(configured, latest - scanBatchSize(profile) + 1L);
                });
    }

    private int scanBatchSize(AccountChainProfile profile) {
        Long maxBlocks = profile.getScanMaxBlocksPerRun();
        if (maxBlocks != null && maxBlocks > 0L) {
            return (int) Math.min(Integer.MAX_VALUE, maxBlocks);
        }
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 50 : batchSize;
    }

    private record AssetScanTarget(String symbol, String coinType, ChainAddressRecord address) {
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
}
