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
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class XrpTransactionService {
    private static final String CHAIN = "XRP";
    private static final String NATIVE_SYMBOL = "XRP";
    private static final int XRP_DECIMALS = 6;

    private final XrpRpcClient rpc;
    private final XrpKeyService keyService;
    private final ChainJdbcRepository repository;
    private final BcSignatureService signatureService = new BcSignatureService();

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public String sendNative(ChainAddressRecord from, String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "xrp sendNative");
        return sendNativeInternal(from, toAddress, amount);
    }

    private String sendNativeInternal(ChainAddressRecord from, String toAddress, BigDecimal amount) {
        validateAddress(toAddress);
        ensureActivated(from.getAddress());
        Payment payment = payment(from, toAddress, XrpCurrencyAmount.of(UnsignedLong.valueOf(toDrops(amount))));
        String txHash = signAndSubmit(from, payment);
        recordTransaction(txHash, from.getAddress(), toAddress, NATIVE_SYMBOL,
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

    public boolean confirmWithdrawal(AccountChainProfile profile, String orderNo, String assetSymbol,
                                     String accountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(CHAIN, orderNo).orElseThrow();
        Confirmation confirmation = confirmation(profile, txHash);
        if (!confirmation.confirmed()) {
            return false;
        }
        if (repository.markWithdrawalConfirmed(CHAIN, orderNo, txHash) == 1) {
            if (!repository.settleLockedDebit(CHAIN, assetSymbol, accountId, debitAmount)) {
                throw new IllegalStateException("unable to settle locked " + assetSymbol + " balance");
            }
            updateConfirmedTransaction(txHash, confirmation);
            return true;
        }
        return false;
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "xrp collectNative");
        Optional<String> previous = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        repository.createCollectionRecord(collectionNo, CHAIN, NATIVE_SYMBOL, from.getAddress(), hotAddress,
                amount, feeAsXrp(), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String txHash = sendNativeInternal(from, hotAddress, amount);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectIssuedCurrency(String collectionNo, ChainAddressRecord from,
                                        TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "xrp collectIssuedCurrency");
        Optional<String> previous = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        repository.createCollectionRecord(collectionNo, CHAIN, token.getSymbol(), from.getAddress(), hotAddress,
                amount, feeAsXrp(), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String txHash = sendIssuedCurrencyInternal(from, token, hotAddress, amount);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmCollection(AccountChainProfile profile, String collectionNo) {
        String txHash = repository.findCollectionTxHash(CHAIN, collectionNo).orElseThrow();
        Confirmation confirmation = confirmation(profile, txHash);
        if (!confirmation.confirmed()) {
            return false;
        }
        boolean updated = repository.markCollectionConfirmed(CHAIN, collectionNo, txHash) == 1;
        updateConfirmedTransaction(txHash, confirmation);
        return updated;
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
            SingleSignedTransaction<Payment> signed = signatureService.sign(privateKey, payment);
            return rpc.submit(signed.signedTransactionBytes().hexValue());
        } finally {
            privateKey.destroy();
        }
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
        boolean hasLine = rpc.accountLines(address, issued.issuer()).findValues("currency").stream()
                .anyMatch(node -> issued.currencyCode().equalsIgnoreCase(node.asText()));
        if (!hasLine) {
            throw new IllegalStateException("XRPL " + side + " account has no trustline for "
                    + issued.symbol() + " issuer=" + issued.issuer());
        }
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
}
