package com.surprising.wallet.model.custody;

import java.math.BigDecimal;

/**
 * 网络费用记录。
 */
public record NetworkFee(
        BigDecimal amount,
        String pricingSource
) {
}
