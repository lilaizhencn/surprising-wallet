package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.common.chain.TokenDefinition;
import org.springframework.util.StringUtils;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;

import java.math.BigDecimal;
import java.util.Locale;
/**
 * XRPL 发行的代币（Issued Currency）定义。
 *
 * <p>XRPL 原生币为 XRP，其他资产以 issuer:currency 格式存在，
 * 需同时指定发行方地址和货币代码。本记录从 {@link TokenDefinition} 解析出这两个要素。
 *
 * @param symbol       资产符号（如 USD、EUR）
 * @param issuer       发行方 XRPL 地址
 * @param currencyCode 货币代码（3 位 ISO 或 40 位十六进制）
 * @param decimals     小数位
 */
public record XrpIssuedCurrency(String symbol, String issuer, String currencyCode, int decimals) {
    public static XrpIssuedCurrency fromToken(TokenDefinition token) {        if (token == null || !StringUtils.hasText(token.getContractAddress())) {
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
