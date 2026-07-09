package com.surprising.wallet.service.chain.xrp;

import com.surprising.wallet.common.chain.TokenDefinition;
import org.springframework.util.StringUtils;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;

import java.math.BigDecimal;
import java.util.Locale;

public record XrpIssuedCurrency(String symbol, String issuer, String currencyCode, int decimals) {
    public static XrpIssuedCurrency fromToken(TokenDefinition token) {
        if (token == null || !StringUtils.hasText(token.getContractAddress())) {
            throw new IllegalArgumentException("XRPL issued currency must be configured as issuer:currency");
        }
        String[] parts = token.getContractAddress().trim().split(":", 2);
        if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            throw new IllegalArgumentException("invalid XRPL issued currency config for "
                    + token.getChain() + "/" + token.getSymbol() + ": expected issuer:currency");
        }
        Address.of(parts[0].trim()).validateAddress();
        return new XrpIssuedCurrency(
                token.getSymbol().toUpperCase(Locale.ROOT),
                parts[0].trim(),
                parts[1].trim().toUpperCase(Locale.ROOT),
                token.getDecimals());
    }

    public boolean matches(String issuer, String currency) {
        return this.issuer.equals(issuer) && this.currencyCode.equalsIgnoreCase(currency);
    }

    public IssuedCurrencyAmount amount(BigDecimal value) {
        return IssuedCurrencyAmount.builder()
                .issuer(Address.of(issuer))
                .currency(currencyCode)
                .value(value.stripTrailingZeros().toPlainString())
                .build();
    }
}
