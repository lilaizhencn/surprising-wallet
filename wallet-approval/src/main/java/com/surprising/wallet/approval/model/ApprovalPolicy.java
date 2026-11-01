package com.surprising.wallet.approval.model;

import com.surprising.wallet.common.approval.WithdrawalIntent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Thresholds always belong to a single tenant/chain/asset. No mixed-currency arithmetic. */
public record ApprovalPolicy(UUID id, UUID tenantId, String chain, String network, String assetSymbol, long version,
        @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal singleAmount,
        @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal rolling24hAmount, boolean allWithdrawals, boolean newRecipient,
        boolean consoleWithdrawal, int requiredReviewers, int expirySeconds) {
    public ApprovalPolicy {
        if (id == null || tenantId == null || version < 1 || chain == null || !chain.matches("[A-Z][A-Z0-9_]{0,31}")
                || network == null || !network.matches("[a-zA-Z0-9_.:-]{1,128}")
                || assetSymbol == null || !assetSymbol.matches("[A-Z][A-Z0-9_.-]{0,31}")
                || requiredReviewers < 1 || requiredReviewers > 2 || expirySeconds < 900 || expirySeconds > 86400)
            throw new IllegalArgumentException("invalid approval policy");
        if (singleAmount != null) singleAmount = WithdrawalIntent.number(singleAmount, true);
        if (rolling24hAmount != null) rolling24hAmount = WithdrawalIntent.number(rolling24hAmount, true);
    }
    public List<String> reasons(WithdrawalIntent intent, BigDecimal previous24hAmount, boolean recipientKnown) {
        if (!tenantId.equals(intent.tenantId()) || !chain.equals(intent.chain()) || !network.equals(intent.network()) || !assetSymbol.equals(intent.assetSymbol()))
            throw new IllegalArgumentException("approval policy owner differs");
        if (previous24hAmount == null || previous24hAmount.signum() < 0) throw new IllegalArgumentException("invalid rolling amount");
        var result = new ArrayList<String>();
        if (allWithdrawals) result.add("ALL_WITHDRAWALS");
        if (singleAmount != null && intent.amount().compareTo(singleAmount) >= 0) result.add("SINGLE_AMOUNT");
        if (rolling24hAmount != null && previous24hAmount.add(intent.amount()).compareTo(rolling24hAmount) >= 0) result.add("ROLLING_24H_AMOUNT");
        if (newRecipient && !recipientKnown) result.add("NEW_RECIPIENT");
        if (consoleWithdrawal && intent.source().equals("CONSOLE")) result.add("CONSOLE_WITHDRAWAL");
        return List.copyOf(result);
    }
}
