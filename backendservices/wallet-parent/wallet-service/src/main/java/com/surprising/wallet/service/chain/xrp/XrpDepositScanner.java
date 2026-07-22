package com.surprising.wallet.service.chain.xrp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.XrpTransactionRecord;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class XrpDepositScanner {
    private static final String CHAIN = "XRP";
    private static final String NATIVE_SYMBOL = "XRP";
    private static final String SCANNER = "xrp-account-tx-scanner";
    private static final int XRP_DECIMALS = 6;

    private final XrpRpcClient rpc;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "xrp scanAndCredit");
        AccountChainProfile profile = profile();
        long latest = rpc.latestLedgerIndex();
        long start = repository.findScanSafeHeight(CHAIN, SCANNER)
                .map(height -> Math.min(height + 1L, latest))
                .orElse(Math.max(0L, latest - scanLimit(profile) + 1L));
        long end = Math.min(latest, start + scanLimit(profile) - 1L);
        List<DepositEvent> events = new ArrayList<>();
        if (start > end) {
            repository.updateScanHeight(CHAIN, SCANNER, latest, latest);
            return events;
        }

        Map<String, ChainAddressRecord> nativeAddresses = repository.listChainAddresses(CHAIN, NATIVE_SYMBOL).stream()
                .filter(address -> "DEPOSIT".equals(address.getWalletRole()))
                .filter(this::isUserDepositAddress)
                .collect(Collectors.toMap(ChainAddressRecord::getAddress, Function.identity(), (a, b) -> a));
        Map<String, TokenDefinition> tokens = tokenMap();
        Map<String, ChainAddressRecord> tokenAddresses = repository.listChainAddresses(CHAIN).stream()
                .filter(address -> !"XRP".equalsIgnoreCase(address.getAssetSymbol()))
                .filter(address -> "DEPOSIT".equals(address.getWalletRole()))
                .filter(this::isUserDepositAddress)
                .collect(Collectors.toMap(
                        address -> address.getAssetSymbol().toUpperCase(Locale.ROOT) + "|" + address.getAddress(),
                        Function.identity(),
                        (a, b) -> a));
        Set<String> scanAddresses = repository.listChainAddresses(CHAIN).stream()
                .filter(address -> "DEPOSIT".equals(address.getWalletRole()))
                .filter(this::isUserDepositAddress)
                .map(ChainAddressRecord::getAddress)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> processed = new HashSet<>();
        for (String address : scanAddresses) {
            ArrayNode transactions = rpc.accountTransactions(address, start, end, scanLimit(profile));
            for (JsonNode entry : transactions) {
                scanEntry(entry, nativeAddresses, tokenAddresses, tokens, profile, latest, processed, events);
            }
        }
        long safeHeight = Math.max(0L, end - profile.getDepositConfirmations() + 1L);
        repository.updateScanHeight(CHAIN, SCANNER, latest, safeHeight);
        return events;
    }

    private void scanEntry(JsonNode entry, Map<String, ChainAddressRecord> nativeAddresses,
                           Map<String, ChainAddressRecord> tokenAddresses,
                           Map<String, TokenDefinition> tokens, AccountChainProfile profile, long latest,
                           Set<String> processed, List<DepositEvent> events) {
        JsonNode tx = txNode(entry);
        if (!"Payment".equals(tx.path("TransactionType").asText())) {
            return;
        }
        JsonNode meta = metaNode(entry);
        if (!"tesSUCCESS".equals(meta.path("TransactionResult").asText())) {
            return;
        }
        String destination = tx.path("Destination").asText("");
        if (destination.isBlank()) {
            return;
        }
        JsonNode amountNode = deliveredAmount(entry);
        if (amountNode == null || amountNode.isMissingNode() || amountNode.isNull()) {
            return;
        }
        String txHash = txHash(entry, tx);
        long ledgerIndex = ledgerIndex(entry, tx);
        int confirmations = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, latest - ledgerIndex + 1L));
        if (amountNode.isTextual()) {
            if (isSystemActivationTransaction(txHash)) {
                return;
            }
            ChainAddressRecord address = nativeAddresses.get(destination);
            if (address == null) {
                return;
            }
            String key = txHash + "|XRP|" + destination;
            if (!processed.add(key)) {
                return;
            }
            BigDecimal amount = new BigDecimal(amountNode.asText()).movePointLeft(XRP_DECIMALS);
            DepositEvent event = new DepositEvent(ChainType.XRP, NATIVE_SYMBOL, txHash,
                    tx.path("Account").asText(""), destination, amount, ledgerIndex, txHash, confirmations,
                    null, entry.toString());
            recordTransaction(event, tx, ledgerIndex, confirmations, null, null, profile);
            repository.recordAndCreditDeposit(event, 0L, profile.getDepositConfirmations(), address.getAccountId());
            events.add(event);
            return;
        }

        String currency = amountNode.path("currency").asText("");
        String issuer = amountNode.path("issuer").asText("");
        TokenDefinition token = tokens.get(tokenKey(issuer, currency));
        if (token == null) {
            return;
        }
        ChainAddressRecord address = tokenAddresses.get(token.getSymbol().toUpperCase(Locale.ROOT) + "|" + destination);
        if (address == null) {
            return;
        }
        String key = txHash + "|" + token.getSymbol() + "|" + destination;
        if (!processed.add(key)) {
            return;
        }
        BigDecimal amount = new BigDecimal(amountNode.path("value").asText("0"));
        DepositEvent event = new DepositEvent(ChainType.XRP, token.getSymbol(), txHash,
                tx.path("Account").asText(""), destination, amount, ledgerIndex, txHash, confirmations,
                token.getContractAddress(), entry.toString());
        recordTransaction(event, tx, ledgerIndex, confirmations, issuer, currency, profile);
        repository.recordAndCreditDeposit(event, token.getId() == null ? 0L : token.getId(),
                profile.getDepositConfirmations(), address.getAccountId());
        events.add(event);
    }

    private void recordTransaction(DepositEvent event, JsonNode tx, long ledgerIndex, int confirmations,
                                   String issuer, String currencyCode, AccountChainProfile profile) {
        repository.recordXrpTransaction(XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(event.txId())
                .fromAddress(event.fromAddress())
                .toAddress(event.toAddress())
                .assetSymbol(event.assetSymbol())
                .issuerAddress(issuer)
                .currencyCode(currencyCode)
                .amount(event.amount())
                .feeDrops(tx.path("Fee").asLong(0))
                .ledgerIndex(ledgerIndex)
                .sequenceNumber(tx.path("Sequence").asLong(0))
                .confirmations(confirmations)
                .status(confirmations >= profile.getDepositConfirmations() ? "CONFIRMED" : "CONFIRMING")
                .rawPayload(event.rawPayload())
                .build());
    }

    private Map<String, TokenDefinition> tokenMap() {
        Map<String, TokenDefinition> tokens = new LinkedHashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            try {
                XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
                tokens.put(tokenKey(issued.issuer(), issued.currencyCode()), token);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed token rows so one bad config does not stop native XRP scanning.
            }
        }
        return tokens;
    }

    private JsonNode txNode(JsonNode entry) {
        JsonNode tx = entry.path("tx_json");
        if (!tx.isMissingNode() && !tx.isNull()) {
            return tx;
        }
        tx = entry.path("tx");
        return tx.isMissingNode() || tx.isNull() ? entry : tx;
    }

    private JsonNode metaNode(JsonNode entry) {
        JsonNode meta = entry.path("meta");
        return meta.isMissingNode() || meta.isNull() ? entry.path("metaData") : meta;
    }

    private JsonNode deliveredAmount(JsonNode entry) {
        JsonNode meta = metaNode(entry);
        JsonNode delivered = meta.path("delivered_amount");
        if (!delivered.isMissingNode() && !delivered.isNull() && !"unavailable".equals(delivered.asText())) {
            return delivered;
        }
        return txNode(entry).path("Amount");
    }

    private String txHash(JsonNode entry, JsonNode tx) {
        String hash = tx.path("hash").asText("");
        if (hash.isBlank()) {
            hash = entry.path("hash").asText("");
        }
        if (hash.isBlank()) {
            hash = entry.path("tx_hash").asText("");
        }
        return hash;
    }

    private long ledgerIndex(JsonNode entry, JsonNode tx) {
        long ledger = entry.path("ledger_index").asLong(0);
        return ledger == 0 ? tx.path("ledger_index").asLong(0) : ledger;
    }

    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 100 : batchSize;
    }

    private boolean isUserDepositAddress(ChainAddressRecord address) {
        return address.getUserId() != HotWalletRules.DEFAULT_HOT_USER_ID;
    }

    private boolean isSystemActivationTransaction(String txHash) {
        return repository.findXrpTransactionAssetSymbol(CHAIN, txHash)
                .filter(XrpTransactionService.ACTIVATION_SYMBOL::equals)
                .isPresent();
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    private String tokenKey(String issuer, String currency) {
        return issuer + "|" + (currency == null ? "" : currency.toUpperCase(Locale.ROOT));
    }
}
