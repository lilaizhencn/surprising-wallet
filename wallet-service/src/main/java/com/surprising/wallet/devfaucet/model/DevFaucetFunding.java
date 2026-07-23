package com.surprising.wallet.devfaucet.model;

import java.math.BigDecimal;
import java.util.UUID;

public record DevFaucetFunding(
        UUID id,
        UUID tenantId,
        UUID custodyAddressId,
        String chain,
        String network,
        String assetSymbol,
        String purpose,
        String address,
        String contractAddress,
        int decimals,
        BigDecimal requestedAmount,
        int attempts
) {
}
