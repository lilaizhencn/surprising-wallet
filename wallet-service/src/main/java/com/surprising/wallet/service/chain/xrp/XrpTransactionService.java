package com.surprising.wallet.service.chain.xrp;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.XrpTransactionRecord;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xrpl.xrpl4j.crypto.keys.PrivateKey;
import org.xrpl.xrpl4j.crypto.signing.SingleSignedTransaction;
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.CurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.TrustSet;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public
class XrpTransactionService {
    private static final String CHAIN = "XRP";    private static final String NATIVE_SYMBOL = "XRP";    public static final String ACTIVATION_SYMBOL = "XRP_ACTIVATION";    private static final int XRP_DECIMALS = 6;    private static final BigDecimal TRUSTLINE_LIMIT = new BigDecimal("1000000000");    private static final int PREPARATION_CONFIRM_ATTEMPTS = 8;
    private static final Duration PREPARATION_CONFIRM_SLEEP = Duration.ofSeconds(2);
    private final XrpRpcClient rpc;    private final XrpKeyService keyService;    private final ChainJdbcRepository repository;
    private final BcSignatureService signatureService = new BcSignatureService();

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    public String sendNative(ChainAddressRecord from, String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "xrp sendNative");
        return sendNativeInternal(from, toAddress, amount);
    }
    private String sendNativeInternal(ChainAddressRecord from, String toAddress, BigDecimal amount) {
        return sendNativeInternal(from, toAddress, amount, NATIVE_SYMBOL);
    }

    private String sendNativeInternal(ChainAddressRecord from, String toAddress, BigDecimal amount,
                                      String recordSymbol) {
        validateAddress(toAddress);
        ensureActivated(from.getAddress());
        Payment payment = payment(from, toAddress, XrpCurrencyAmount.of(UnsignedLong.valueOf(toDrops(amount))));
        String txHash = signAndSubmit(from, payment);
        recordTransaction(txHash, from.getAddress(), toAddress, recordSymbol,
                null, null, amount, payment.fee().value().longValue(), "SENT", null);
        return txHash;
    }

    public String sendIssuedCurrency(ChainAddressRecord from, TokenDefinition token,
                                     String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "xrp sendIssuedCurrency");
        return sendIssuedCurrencyInternal(from, token, toAddress, amount);
    }

    private String sendIssuedCurrencyInternal(ChainAddressRecord from, TokenDefinition token,
                                             String toAddress, BigDecimal amount) {
        validateAddress(toAddress);
        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
        ensureActivated(from.getAddress());
        ensureActivated(toAddress);
        ensureTrustLine(from.getAddress(), issued, "source");
        if (!toAddress.equals(issued.issuer())) {
            ensureTrustLine(toAddress, issued, "destination");
        }
        Payment payment = payment(from, toAddress, issued.amount(amount));
        String txHash = signAndSubmit(from, payment);
        recordTransaction(txHash, from.getAddress(), toAddress, token.getSymbol(),
                issued.issuer(), issued.currencyCode(), amount, payment.fee().value().longValue(), "SENT", null);
        return txHash;
    }

    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        Confirmation confirmation = confirmation(profile, txHash);
        if (!confirmation.confirmed()) {
            return false;
        }
        if (repository.confirmWithdrawalAndSettle(
                tenantId, CHAIN, orderNo, txHash, assetSymbol, accountId, debitAmount)) {
            updateConfirmedTransaction(txHash, confirmation);
            return true;
        }
        return false;
    }

    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "xrp collectNative");
        Optional<String> previous = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String txHash = sendNativeInternal(from, hotAddress, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    public BigDecimal collectableNativeAmount(String address, BigDecimal candidateAmount) {
        BigDecimal candidate = candidateAmount == null ? BigDecimal.ZERO : candidateAmount;
        if (candidate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Optional<XrpRpcClient.AccountState> account = rpc.accountInfo(address);
        if (account.isEmpty()) {
            return BigDecimal.ZERO;
        }
        XrpRpcClient.ReserveInfo reserve = rpc.reserveInfo();
        int ownerSlots = account.get().ownerCount() + missingIssuedCurrencyTrustLines(address);
        BigDecimal balance = fromDrops(account.get().balanceDrops());
        BigDecimal requiredReserve = reserve.baseXrp()
                .add(reserve.ownerXrp().multiply(BigDecimal.valueOf(ownerSlots)));
        BigDecimal feeReserve = fromDrops(BigDecimal.valueOf(feeDrops()));
        BigDecimal spendable = balance.subtract(requiredReserve).subtract(feeReserve);
        if (spendable.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return candidate.min(spendable).stripTrailingZeros();
    }
    private int missingIssuedCurrencyTrustLines(String address) {
        Map<String, TokenDefinition> tokens = new HashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            tokens.put(token.getSymbol().toUpperCase(Locale.ROOT), token);
        }
        Set<String> checkedSymbols = new HashSet<>();
        int missing = 0;
        for (ChainAddressRecord record : repository.listChainAddresses(CHAIN)) {
            if (NATIVE_SYMBOL.equalsIgnoreCase(record.getAssetSymbol())
                    || !address.equals(record.getAddress())) {
                continue;
            }
            String symbol = record.getAssetSymbol().toUpperCase(Locale.ROOT);
            if (!checkedSymbols.add(symbol)) {
                continue;
            }
            TokenDefinition token = tokens.get(symbol);
            if (token == null) {
                continue;
            }
            try {
                XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
                if (!hasTrustLine(address, issued)) {
                    missing++;
                }
            } catch (IllegalArgumentException ignored) {
                // Bad token rows should not stop XRP native collection.
            }
        }
        return missing;
    }

    public String collectIssuedCurrency(java.util.UUID tenantId, String collectionNo,
                                        ChainAddressRecord from,
                                        TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "xrp collectIssuedCurrency");
        Optional<String> previous = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        ensureCollectionDestinationTrustLine(token, hotAddress);
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String txHash = sendIssuedCurrencyInternal(from, token, hotAddress, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    private void ensureCollectionDestinationTrustLine(TokenDefinition token, String hotAddress) {
        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
        if (hotAddress.equals(issued.issuer()) || hasTrustLine(hotAddress, issued)) {
            return;
        }
        ChainAddressRecord hot = repository.findChainAddressByAddress(CHAIN, NATIVE_SYMBOL, hotAddress)
                .or(() -> repository.findChainAddressByAddress(CHAIN, hotAddress))
                .orElseThrow(() -> new IllegalStateException(
                        "missing XRP hot wallet address for issued-currency collection: " + hotAddress));
        String trustSetTxHash = createTrustLine(hot, issued);
        Confirmation confirmation = waitForValidated(trustSetTxHash, "XRPL hot wallet trustline creation");
        updateSystemTransaction(trustSetTxHash, token.getSymbol(), issued.issuer(),
                issued.currencyCode(), confirmation);
    }

    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        Confirmation confirmation = confirmation(profile, txHash);
        if (!confirmation.confirmed()) {
            return false;
        }
        boolean updated = repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
        updateConfirmedTransaction(txHash, confirmation);
        return updated;
    }
    public DepositPreparation ensureIssuedCurrencyDepositReady(ChainAddressRecord depositAddress, String symbol) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_TRANSFER, "xrp prepareIssuedCurrencyDepositAddress");
        TokenDefinition token = token(symbol);
        XrpIssuedCurrency issued = XrpIssuedCurrency.fromToken(token);
        validateAddress(depositAddress.getAddress());

        Optional<XrpRpcClient.AccountState> accountBefore = rpc.accountInfo(depositAddress.getAddress());
        boolean activatedBefore = accountBefore.isPresent();
        boolean trustLineBefore = activatedBefore && hasTrustLine(depositAddress.getAddress(), issued);
        String activationTxHash = null;
        String trustSetTxHash = null;
        BigDecimal activationAmount = BigDecimal.ZERO;

        if (!trustLineBefore) {
            BigDecimal topUp = requiredTrustLineTopUp(accountBefore, trustLineBefore);
            if (topUp.signum() > 0) {
                ChainAddressRecord hotAddress = defaultHotAddress();
                BigDecimal spendable = collectableNativeAmount(hotAddress.getAddress(), topUp);
                if (spendable.compareTo(topUp) < 0) {
                    throw new IllegalStateException("XRPL hot wallet does not have enough spendable XRP for address activation");
                }
                activationAmount = topUp;
                activationTxHash = sendNativeInternal(hotAddress, depositAddress.getAddress(), topUp, ACTIVATION_SYMBOL);
                Confirmation confirmation = waitForValidated(activationTxHash, "XRPL address activation");
                updateSystemTransaction(activationTxHash, ACTIVATION_SYMBOL, null, null, confirmation);
            }
            if (!hasTrustLine(depositAddress.getAddress(), issued)) {
                trustSetTxHash = createTrustLine(depositAddress, issued);
                Confirmation confirmation = waitForValidated(trustSetTxHash, "XRPL trustline creation");
                updateSystemTransaction(trustSetTxHash, token.getSymbol(), issued.issuer(),
                        issued.currencyCode(), confirmation);
            }
        }

        boolean activated = rpc.accountInfo(depositAddress.getAddress()).isPresent();
        boolean trustLineReady = activated && hasTrustLine(depositAddress.getAddress(), issued);
        return new DepositPreparation(activated, trustLineReady, activatedBefore, trustLineBefore,
                activationAmount, activationTxHash, trustSetTxHash);
    }
    public boolean hasIssuedCurrencyTrustLine(String address, String symbol) {
        return hasTrustLine(address, XrpIssuedCurrency.fromToken(token(symbol)));
    }
    private Payment payment(ChainAddressRecord from, String toAddress, CurrencyAmount amount) {
        long feeDrops = feeDrops();
        long sequence = rpc.accountSequence(from.getAddress());
        long lastLedger = rpc.latestLedgerIndex() + 20L;
        return Payment.builder()
                .account(Address.of(from.getAddress()))
                .destination(Address.of(toAddress))
                .amount(amount)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(feeDrops)))
                .sequence(UnsignedInteger.valueOf(sequence))
                .lastLedgerSequence(UnsignedInteger.valueOf(lastLedger))
                .build();
    }
    private String signAndSubmit(ChainAddressRecord from, Payment payment) {
        AccountChainProfile profile = profile();
        PrivateKey privateKey = keyService.privateKey(profile, from);
        try {
            Payment signable = Payment.builder()
                    .from(payment)
                    .signingPublicKey(signatureService.derivePublicKey(privateKey))
                    .build();
            SingleSignedTransaction<Payment> signed = signatureService.sign(privateKey, signable);
            return rpc.submit(signed.signedTransactionBytes().hexValue());
        } finally {
            privateKey.destroy();
        }
    }
    private String signAndSubmit(ChainAddressRecord from, TrustSet trustSet) {
        AccountChainProfile profile = profile();
        PrivateKey privateKey = keyService.privateKey(profile, from);
        try {
            TrustSet signable = TrustSet.builder()
                    .from(trustSet)
                    .signingPublicKey(signatureService.derivePublicKey(privateKey))
                    .build();
            SingleSignedTransaction<TrustSet> signed = signatureService.sign(privateKey, signable);
            return rpc.submit(signed.signedTransactionBytes().hexValue());
        } finally {
            privateKey.destroy();
        }
    }
    private String createTrustLine(ChainAddressRecord address, XrpIssuedCurrency issued) {
        ensureActivated(address.getAddress());
        long feeDrops = feeDrops();
        long sequence = rpc.accountSequence(address.getAddress());
        long lastLedger = rpc.latestLedgerIndex() + 20L;
        IssuedCurrencyAmount limit = IssuedCurrencyAmount.builder()
                .issuer(Address.of(issued.issuer()))
                .currency(issued.currencyCode())
                .value(TRUSTLINE_LIMIT.stripTrailingZeros().toPlainString())
                .build();
        TrustSet trustSet = TrustSet.builder()
                .account(Address.of(address.getAddress()))
                .limitAmount(limit)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(feeDrops)))
                .sequence(UnsignedInteger.valueOf(sequence))
                .lastLedgerSequence(UnsignedInteger.valueOf(lastLedger))
                .build();
        String txHash = signAndSubmit(address, trustSet);
        recordTransaction(txHash, address.getAddress(), issued.issuer(), issued.symbol(),
                issued.issuer(), issued.currencyCode(), BigDecimal.ZERO,
                trustSet.fee().value().longValue(), "SENT", null);
        return txHash;
    }

    private BigDecimal requiredTrustLineTopUp(Optional<XrpRpcClient.AccountState> currentAccount,
                                              boolean trustLineExists) {
        if (trustLineExists) {
            return BigDecimal.ZERO;
        }
        XrpRpcClient.ReserveInfo reserve = rpc.reserveInfo();
        BigDecimal balance = currentAccount
                .map(account -> fromDrops(account.balanceDrops()))
                .orElse(BigDecimal.ZERO);
        int ownerCount = currentAccount.map(XrpRpcClient.AccountState::ownerCount).orElse(0);
        BigDecimal requiredReserve = reserve.baseXrp()
                .add(reserve.ownerXrp().multiply(BigDecimal.valueOf(ownerCount + 1L)));
        BigDecimal feeCushion = fromDrops(BigDecimal.valueOf(feeDrops())).multiply(BigDecimal.valueOf(3L));
        return requiredReserve.add(feeCushion)
                .subtract(balance)
                .setScale(XRP_DECIMALS, RoundingMode.CEILING)
                .max(BigDecimal.ZERO)
                .stripTrailingZeros();
    }
    private ChainAddressRecord defaultHotAddress() {
        return repository.listDefaultHotAddressCandidates(CHAIN, NATIVE_SYMBOL).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing XRP default hot wallet address"));
    }
    private TokenDefinition token(String symbol) {
        String value = symbol == null ? "" : symbol.trim();
        return repository.listTokens(CHAIN).stream()
                .filter(candidate -> candidate.getSymbol().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("XRPL issued currency not configured: " + symbol));
    }
    private Confirmation waitForValidated(String txHash, String operation) {
        AccountChainProfile profile = profile();
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < PREPARATION_CONFIRM_ATTEMPTS; attempt++) {
            try {
                Confirmation confirmation = confirmation(profile, txHash);
                if (confirmation.confirmed()) {
                    return confirmation;
                }
            } catch (RuntimeException e) {
                lastFailure = e;
            }
            sleepBeforeConfirmationRetry();
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IllegalStateException(operation + " was submitted but not confirmed in time: " + txHash);
    }
    private void sleepBeforeConfirmationRetry() {
        try {
            Thread.sleep(PREPARATION_CONFIRM_SLEEP.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for XRPL confirmation", e);
        }
    }

    private void updateSystemTransaction(String txHash, String symbol, String issuer,
                                         String currencyCode, Confirmation confirmation) {
        JsonNode tx = txNode(confirmation.raw());
        repository.recordXrpTransaction(XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(tx.path("Account").asText(""))
                .toAddress(tx.path("Destination").asText(issuer == null ? "" : issuer))
                .assetSymbol(symbol)
                .issuerAddress(issuer)
                .currencyCode(currencyCode)
                .amount(symbol.equals(ACTIVATION_SYMBOL)
                        ? fromDrops(new BigDecimal(tx.path("Amount").asText("0")))
                        : BigDecimal.ZERO)
                .feeDrops(tx.path("Fee").asLong(0))
                .ledgerIndex(confirmation.ledgerIndex())
                .sequenceNumber(tx.path("Sequence").asLong(0))
                .confirmations((int) Math.min(Integer.MAX_VALUE, confirmation.confirmations()))
                .status("CONFIRMED")
                .rawPayload(confirmation.raw().toString())
                .build());
    }
    private Confirmation confirmation(AccountChainProfile profile, String txHash) {
        JsonNode result = rpc.transaction(txHash);
        if (!result.path("validated").asBoolean(false)) {
            return new Confirmation(false, 0, 0, result);
        }
        JsonNode tx = txNode(result);
        JsonNode meta = metaNode(result);
        if (!"tesSUCCESS".equals(meta.path("TransactionResult").asText())) {
            throw new IllegalStateException("XRPL transaction failed: "
                    + meta.path("TransactionResult").asText());
        }
        long ledger = result.path("ledger_index").asLong(tx.path("ledger_index").asLong(0));
        long confirmations = Math.max(1L, rpc.latestLedgerIndex() - ledger + 1L);
        return new Confirmation(confirmations >= Math.max(1, profile.getWithdrawConfirmations()),
                ledger, confirmations, result);
    }
    private void updateConfirmedTransaction(String txHash, Confirmation confirmation) {
        JsonNode tx = txNode(confirmation.raw());
        JsonNode amountNode = deliveredAmount(confirmation.raw());
        String from = tx.path("Account").asText("");
        String to = tx.path("Destination").asText("");
        XrpTransactionRecord.XrpTransactionRecordBuilder builder = XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .ledgerIndex(confirmation.ledgerIndex())
                .sequenceNumber(tx.path("Sequence").asLong(0))
                .confirmations((int) Math.min(Integer.MAX_VALUE, confirmation.confirmations()))
                .feeDrops(tx.path("Fee").asLong(0))
                .status("CONFIRMED")
                .rawPayload(confirmation.raw().toString());
        if (amountNode.isTextual()) {
            builder.assetSymbol(NATIVE_SYMBOL)
                    .amount(fromDrops(new BigDecimal(amountNode.asText())));
        } else {
            String currency = amountNode.path("currency").asText("");
            String issuer = amountNode.path("issuer").asText("");
            TokenDefinition token = repository.listTokens(CHAIN).stream()
                    .filter(candidate -> matchesIssuedCurrency(candidate, issuer, currency))
                    .findFirst()
                    .orElse(null);
            builder.assetSymbol(token == null ? currency : token.getSymbol())
                    .issuerAddress(issuer)
                    .currencyCode(currency)
                    .amount(new BigDecimal(amountNode.path("value").asText("0")));
        }
        repository.recordXrpTransaction(builder.build());
    }
    private boolean matchesIssuedCurrency(TokenDefinition token, String issuer, String currency) {
        try {
            return XrpIssuedCurrency.fromToken(token).matches(issuer, currency);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void recordTransaction(String txHash, String from, String to, String symbol,
                                   String issuer, String currencyCode, BigDecimal amount,
                                   long feeDrops, String status, String rawPayload) {
        repository.recordXrpTransaction(XrpTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .issuerAddress(issuer)
                .currencyCode(currencyCode)
                .amount(amount)
                .feeDrops(feeDrops)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }
    private void ensureTrustLine(String address, XrpIssuedCurrency issued, String side) {
        if (!hasTrustLine(address, issued)) {
            throw new IllegalStateException("XRPL " + side + " account has no trustline for "
                    + issued.symbol() + " issuer=" + issued.issuer());
        }
    }
    private boolean hasTrustLine(String address, XrpIssuedCurrency issued) {
        return rpc.accountLines(address, issued.issuer()).findValues("currency").stream()
                .anyMatch(node -> issued.currencyCode().equalsIgnoreCase(node.asText()));
    }
    private void ensureActivated(String address) {
        if (rpc.accountInfo(address).isEmpty()) {
            throw new IllegalStateException("XRPL account is not activated: " + address);
        }
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private long feeDrops() {
        Long configured = profile().getDefaultFee();
        return configured == null || configured <= 0 ? rpc.feeDrops() : Math.max(10L, configured);
    }
    private BigDecimal feeAsXrp() {
        return fromDrops(BigDecimal.valueOf(feeDrops()));
    }
    private long toDrops(BigDecimal amount) {
        return amount.movePointRight(XRP_DECIMALS)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }
    private BigDecimal fromDrops(BigDecimal drops) {
        return drops.movePointLeft(XRP_DECIMALS).stripTrailingZeros();
    }
    private JsonNode txNode(JsonNode result) {
        JsonNode txJson = result.path("tx_json");
        return txJson.isMissingNode() || txJson.isNull() ? result : txJson;
    }
    private JsonNode metaNode(JsonNode result) {
        JsonNode meta = result.path("meta");
        return meta.isMissingNode() || meta.isNull() ? result.path("metaData") : meta;
    }
    private JsonNode deliveredAmount(JsonNode result) {
        JsonNode meta = metaNode(result);
        JsonNode delivered = meta.path("delivered_amount");
        if (!delivered.isMissingNode() && !delivered.isNull() && !"unavailable".equals(delivered.asText())) {
            return delivered;
        }
        return txNode(result).path("Amount");
    }
    private void validateAddress(String address) {
        Address.of(address).validateAddress();
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    private record Confirmation(boolean confirmed, long ledgerIndex, long confirmations, JsonNode raw) {
    }

    public record DepositPreparation(
            boolean activated,
            boolean trustLineReady,
            boolean activatedBefore,
            boolean trustLineBefore,
            BigDecimal activationAmount,
            String activationTxHash,
            String trustSetTxHash
    ) {
    }
}
