package com.surprising.wallet.model.custody;

import java.math.BigDecimal;
import java.time.Instant;

public record AssetPriceRecord(String assetSymbol, BigDecimal usdPrice, String source,
                               Instant observedAt, Instant updatedAt) { }
