package com.surprising.wallet.sdk.signing.sui;

import java.math.BigDecimal;

/** Public reference and exact atomic balance, independently verified before signing. */
public record SuiCoin(String objectId,String version,String digest,BigDecimal balance) { }
