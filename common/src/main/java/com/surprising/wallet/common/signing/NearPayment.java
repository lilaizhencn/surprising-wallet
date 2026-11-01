package com.surprising.wallet.common.signing;

import java.math.BigDecimal;

/** Immutable original NEAR parent payment, distinct from the Gas-funded storage deposit. */
public record NearPayment(String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) { }
