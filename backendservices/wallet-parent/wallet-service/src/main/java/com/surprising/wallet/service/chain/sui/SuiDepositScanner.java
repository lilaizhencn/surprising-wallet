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
import java.util.List;

@Service
@RequiredArgsConstructor
public class SuiDepositScanner {
    private static final String CHAIN = "SUI";
    private static final String SCANNER = "sui-balance-change-scanner";

    private final SuiRpcClient rpc;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "sui scanAndCredit");
        AccountChainProfile profile = profile();
        long checkpoint = rpc.latestCheckpoint();
        List<DepositEvent> events = new ArrayList<>();
        scanSymbol("SUI", SuiRpcClient.SUI_COIN_TYPE, profile, checkpoint, events);
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            if (token.getContractAddress() != null && Boolean.TRUE.equals(token.getActive())) {
                scanSymbol(token.getSymbol(), token.getContractAddress(), profile, checkpoint, events);
            }
        }
        repository.updateScanHeight(CHAIN, SCANNER, checkpoint, checkpoint);
        return events;
    }

    private void scanSymbol(String symbol, String coinType, AccountChainProfile profile,
                            long bestCheckpoint, List<DepositEvent> events) {
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, symbol)) {
            if (!"DEPOSIT".equals(address.getWalletRole())) {
                continue;
            }
            JsonNode page = rpc.queryToAddress(address.getAddress(), null, scanLimit(profile), true);
            JsonNode data = page.path("data");
            for (int txIndex = 0; txIndex < data.size(); txIndex++) {
                scanTransaction(data.get(txIndex), symbol, coinType, address, profile, bestCheckpoint, events);
            }
        }
    }

    private void scanTransaction(JsonNode transaction, String symbol, String coinType,
                                 ChainAddressRecord address, AccountChainProfile profile,
                                 long bestCheckpoint, List<DepositEvent> events) {
        String sender = SuiHex.normalizeAddress(transaction.path("transaction").path("data").path("sender")
                .asText("0x0"));
        if (isPlatformAddress(sender)) {
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
                    address.getAddress(), amount, checkpoint, confirmations,
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

    private boolean isPlatformAddress(String address) {
        for (ChainAddressRecord tracked : repository.listChainAddresses(CHAIN)) {
            if (sameAddress(address, tracked.getAddress()) || sameAddress(address, tracked.getOwnerAddress())) {
                return true;
            }
        }
        return false;
    }

    private boolean sameAddress(String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank()) {
            return false;
        }
        try {
            return SuiHex.normalizeAddress(first).equals(SuiHex.normalizeAddress(second));
        } catch (RuntimeException ignored) {
            return false;
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

    private int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 50 : batchSize;
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
}
