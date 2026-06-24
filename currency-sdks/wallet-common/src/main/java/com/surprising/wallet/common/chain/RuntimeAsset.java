package com.surprising.wallet.common.chain;

import com.surprising.wallet.common.exception.UnsupportedCurrency;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Static compatibility metadata for legacy wallet/signer classes.
 *
 * <p>Runtime asset identity is {@code chain + assetSymbol}. New chains and
 * tokens must be configured through DB Asset Model tables instead of adding
 * entries here. This class exists only while legacy wallet beans and signer
 * queues still need integer ids, decimals, and BIP44 metadata.</p>
 */
public final class RuntimeAsset {
    public static final RuntimeAsset BTC = nativeAsset(1, "BTC", 7, 1, 6, Decimal.EIGHT, 1);
    public static final RuntimeAsset LTC = nativeAsset(24, "LTC", 7, 1, 6, Decimal.EIGHT, 2);
    public static final RuntimeAsset DOGE = nativeAsset(41, "DOGE", 13, 6, 12, Decimal.EIGHT, 3);
    public static final RuntimeAsset BCH = nativeAsset(42, "BCH", 7, 1, 6, Decimal.EIGHT, 145);
    public static final RuntimeAsset ETH = nativeAsset(2, "ETH", 121, 12, 120, Decimal.EIGHTEEN, 60);
    public static final RuntimeAsset TRX = nativeAsset(23, "TRON", "TRX", 121, 12, 120, Decimal.SIX, 195);
    public static final RuntimeAsset USDT = tokenAsset(
            100, "ETH", "USDT", 121, 12, 120, Decimal.SIX,
            "0xdac17f958d2ee523a2206206994597c13d831ec7");

    public static final Set<RuntimeAsset> ERC20_SET = Set.of(USDT);

    private static final List<RuntimeAsset> KNOWN = List.of(BTC, LTC, DOGE, BCH, ETH, TRX, USDT);
    private static final Map<Integer, RuntimeAsset> BY_ID =
            KNOWN.stream().collect(Collectors.toUnmodifiableMap(RuntimeAsset::getIndex, Function.identity()));
    private static final Map<String, RuntimeAsset> BY_SYMBOL =
            KNOWN.stream().collect(Collectors.toUnmodifiableMap(
                    asset -> asset.getName().toUpperCase(Locale.ROOT), Function.identity(), (left, right) -> left));

    private final int index;
    private final String chain;
    private final String assetSymbol;
    private final long confirmNum;
    private final long depositConfirmNum;
    private final long withdrawConfirmNum;
    private final BigDecimal decimal;
    private final String contractAddress;
    private final int bip44CoinType;

    private RuntimeAsset(int index, String chain, String assetSymbol, int confirmNum, int depositConfirmNum,
                         int withdrawConfirmNum, Decimal decimal, String contractAddress, int bip44CoinType) {
        this.index = index;
        this.chain = chain.toUpperCase(Locale.ROOT);
        this.assetSymbol = assetSymbol.toUpperCase(Locale.ROOT);
        this.confirmNum = confirmNum;
        this.depositConfirmNum = depositConfirmNum;
        this.withdrawConfirmNum = withdrawConfirmNum;
        this.decimal = BigDecimal.valueOf(decimal.getDecimal());
        this.contractAddress = contractAddress == null ? "" : contractAddress;
        this.bip44CoinType = bip44CoinType;
    }

    private static RuntimeAsset nativeAsset(int index, String chain, int confirmNum, int depositConfirmNum,
                                            int withdrawConfirmNum, Decimal decimal, int bip44CoinType) {
        return nativeAsset(index, chain, chain, confirmNum, depositConfirmNum, withdrawConfirmNum,
                decimal, bip44CoinType);
    }

    private static RuntimeAsset nativeAsset(int index, String chain, String assetSymbol, int confirmNum,
                                            int depositConfirmNum, int withdrawConfirmNum, Decimal decimal,
                                            int bip44CoinType) {
        return new RuntimeAsset(index, chain, assetSymbol, confirmNum, depositConfirmNum, withdrawConfirmNum,
                decimal, "", bip44CoinType);
    }

    private static RuntimeAsset tokenAsset(int index, String chain, String assetSymbol, int confirmNum,
                                           int depositConfirmNum, int withdrawConfirmNum, Decimal decimal,
                                           String contractAddress) {
        return new RuntimeAsset(index, chain, assetSymbol, confirmNum, depositConfirmNum, withdrawConfirmNum,
                decimal, contractAddress, ETH.bip44CoinType);
    }

    public static RuntimeAsset parseValue(int index) {
        RuntimeAsset asset = BY_ID.get(index);
        if (asset == null) {
            throw new UnsupportedCurrency(String.valueOf(index));
        }
        return asset;
    }

    public static RuntimeAsset parseName(String name) {
        if (StringUtils.hasText(name)) {
            RuntimeAsset asset = BY_SYMBOL.get(name.toUpperCase(Locale.ROOT));
            if (asset != null) {
                return asset;
            }
        }
        throw new UnsupportedCurrency(name);
    }

    public static RuntimeAsset valueOf(String name) {
        return parseName(name);
    }

    public static RuntimeAsset parseContract(String contract) {
        if (StringUtils.hasText(contract)) {
            for (RuntimeAsset asset : KNOWN) {
                if (StringUtils.hasText(asset.getContractAddress())
                        && asset.getContractAddress().equalsIgnoreCase(contract)) {
                    return asset;
                }
            }
        }
        return null;
    }

    public static boolean isErc20(RuntimeAsset asset) {
        return asset == USDT;
    }

    public static RuntimeAsset toMainCurrency(RuntimeAsset asset) {
        return isErc20(asset) ? ETH : asset;
    }

    public String chain() {
        return chain;
    }

    public String assetSymbol() {
        return assetSymbol;
    }

    public String name() {
        return assetSymbol;
    }

    public long getConfirmNum() {
        return confirmNum;
    }

    public long getDepositConfirmNum() {
        return depositConfirmNum;
    }

    public long getWithdrawConfirmNum() {
        return withdrawConfirmNum;
    }

    public BigDecimal getDecimal() {
        return decimal;
    }

    public int getIndex() {
        return index;
    }

    public int getBip44CoinType() {
        return bip44CoinType;
    }

    public String getName() {
        return assetSymbol.toLowerCase(Locale.ROOT);
    }

    public String getContractAddress() {
        return contractAddress;
    }

    @Override
    public String toString() {
        return chain + ":" + assetSymbol;
    }

    enum Decimal {
        SIX(1_000_000L),
        EIGHT(100_000_000L),
        EIGHTEEN(1_000_000_000_000_000_000L);

        private final long decimal;

        Decimal(long decimal) {
            this.decimal = decimal;
        }

        public long getDecimal() {
            return decimal;
        }
    }
}
