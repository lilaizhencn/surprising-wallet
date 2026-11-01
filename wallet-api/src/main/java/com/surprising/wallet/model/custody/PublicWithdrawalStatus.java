package com.surprising.wallet.model.custody;

/** Internal authorization holds remain ordinary accepted/frozen withdrawals on every public surface. */
public final class PublicWithdrawalStatus {
    private PublicWithdrawalStatus() { }
    public static String of(String internal) { return "PENDING_REVIEW".equals(internal)?"FROZEN":internal; }
    public static String message(String internal,String message) { return "PENDING_REVIEW".equals(internal)?null:message; }
}
