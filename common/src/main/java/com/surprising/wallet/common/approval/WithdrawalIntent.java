package com.surprising.wallet.common.approval;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Stable business facts reviewed before short-lived nonce, UTXO or block hash preparation. */
public record WithdrawalIntent(UUID id, UUID tenantId, String businessReference, String chain, String network,
        String assetSymbol, String recipient, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount,
        @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal maximumNetworkFee,
        String source, UUID initiatorId, String subject, String externalReference, String fundingSource) {
    public WithdrawalIntent {
        if (id == null || tenantId == null || initiatorId == null) throw new IllegalArgumentException("intent identity required");
        businessReference = text(businessReference, 192); recipient = text(recipient, 256);
        chain = token(chain, "[A-Z][A-Z0-9_]{0,31}"); network = token(network, "[a-zA-Z0-9_.:-]{1,128}");
        assetSymbol = token(assetSymbol, "[A-Z][A-Z0-9_.-]{0,31}");
        if (source==null || !java.util.Set.of("API", "CONSOLE", "SYSTEM").contains(source)) throw new IllegalArgumentException("invalid intent source");
        amount = number(amount, !source.equals("SYSTEM")); maximumNetworkFee = number(maximumNetworkFee, false);
        subject = subject == null || subject.isEmpty() ? "" : text(subject, 192);
        externalReference=externalReference==null || externalReference.isEmpty()?"":text(externalReference,192);
        if(fundingSource==null || !java.util.Set.of("ADDRESS","TENANT_POOL").contains(fundingSource)) throw new IllegalArgumentException("explicit intent funding source required");
    }
    public String hash(ObjectMapper json) {
        var facts = new TreeMap<String, Object>(Map.of("id", id.toString(), "tenantId", tenantId.toString(),
                "businessReference", businessReference, "chain", chain, "network", network,
                "assetSymbol", assetSymbol, "recipient", recipient, "amount", amount.toPlainString(),
                "maximumNetworkFee", maximumNetworkFee.toPlainString(), "source", source));
        facts.put("initiatorId", initiatorId.toString()); facts.put("subject", subject);
        facts.put("externalReference",externalReference);facts.put("fundingSource",fundingSource);
        return SigningAuthorization.hash(json.writeValueAsBytes(facts));
    }
    public static BigDecimal number(BigDecimal value, boolean positive) {
        if (value == null || (positive ? value.signum() <= 0 : value.signum() < 0)) throw new IllegalArgumentException("invalid intent amount");
        BigDecimal normalized=value.stripTrailingZeros();
        // Bound exponents before setScale can allocate an attacker-sized power of ten.
        if(normalized.scale()>24 || (long)normalized.precision()-normalized.scale()>60)
            throw new IllegalArgumentException("amount exceeds stored precision or range");
        return normalized.scale()<0?normalized.setScale(0):normalized;
    }
    private static String token(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("invalid intent domain");
        return value;
    }
    private static String text(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid intent text");
        return value;
    }
}
