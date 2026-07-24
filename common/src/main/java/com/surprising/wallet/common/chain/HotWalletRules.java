package com.surprising.wallet.common.chain;

/**
 * 热钱包地址规则。
 *
 * <p>定义 userId=0、biz=0 为系统保留热钱包标识，
 * 仅允许该组合下的原生代币默认热钱包地址（address_index=0、
 * wallet_role=DEPOSIT），其他 userId=0 的资产将被拒绝，
 * 防止误用保留地址空间。</p>
 */
public final class HotWalletRules {
    public static final long DEFAULT_HOT_USER_ID = 0L;
    public static final int DEFAULT_HOT_BIZ = 0;
    public static final long DEFAULT_HOT_ADDRESS_INDEX = 0L;
    public static final String DEFAULT_HOT_WALLET_ROLE = "DEPOSIT";

    private HotWalletRules() {
    }

    public static boolean isDefaultHotUser(Long userId, Integer biz) {
        return userId != null
                && biz != null
                && userId == DEFAULT_HOT_USER_ID
                && biz == DEFAULT_HOT_BIZ;
    }

    public static boolean isDefaultHotAddress(long userId, int biz, long addressIndex, String walletRole) {
        return userId == DEFAULT_HOT_USER_ID
                && biz == DEFAULT_HOT_BIZ
                && addressIndex == DEFAULT_HOT_ADDRESS_INDEX
                && DEFAULT_HOT_WALLET_ROLE.equals(walletRole);
    }

    public static void requireAllowedReservedAddress(String chain, String assetSymbol, String nativeSymbol,
                                                     long userId, int biz, long addressIndex, String walletRole) {
        if (userId != DEFAULT_HOT_USER_ID || biz != DEFAULT_HOT_BIZ) {
            return;
        }
        if (nativeSymbol.equalsIgnoreCase(assetSymbol)
                && isDefaultHotAddress(userId, biz, addressIndex, walletRole)) {
            return;
        }
        throw new IllegalArgumentException("userId=0,biz=0 is reserved for the unique native default hot wallet "
                + chain + "/" + nativeSymbol + " address_index=0 wallet_role=DEPOSIT");
    }
}
