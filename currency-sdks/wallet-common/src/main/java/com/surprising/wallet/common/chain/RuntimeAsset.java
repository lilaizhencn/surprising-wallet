package com.surprising.wallet.common.chain;

import com.surprising.wallet.common.exception.UnsupportedCurrency;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime asset metadata value.
 *
 * <p>Runtime currency ids must come from DB Asset Model rows
 * ({@code chain_profile.runtime_currency_id}) or from persisted transaction
 * payloads. This class intentionally does not hard-code any currency id.</p>
 */
public final class RuntimeAsset {
    public static final RuntimeAsset BTC = nativeAsset("BTC", 7, 1, 6, 0);
    public static final RuntimeAsset LTC = nativeAsset("LTC", 7, 1, 6, 2);
    public static final RuntimeAsset DOGE = nativeAsset("DOGE", 13, 6, 12, 3);
    public static final RuntimeAsset BCH = nativeAsset("BCH", 7, 1, 6, 145);
    public static final RuntimeAsset ETH = nativeAsset("ETH", 121, 12, 120, 60);
    public static final RuntimeAsset TRX = nativeAsset("TRON", "TRX", 121, 12, 120, 195);
    public static final RuntimeAsset USDT = tokenAsset("ETH", "USDT", 121, 12, 120, 60);

    private final Integer runtimeCurrencyId;
    private final String chain;
    private final String assetSymbol;
    private final long confirmNum;
    private final long depositConfirmNum;
    private final long withdrawConfirmNum;
    private final Integer decimals;
    private final String contractAddress;
    private final int bip44CoinType;

    private RuntimeAsset(Integer runtimeCurrencyId, String chain, String assetSymbol, long confirmNum,
                         long depositConfirmNum, long withdrawConfirmNum, Integer decimals,
                         String contractAddress, int bip44CoinType) {
        this.runtimeCurrencyId = runtimeCurrencyId;
        this.chain = chain.toUpperCase(Locale.ROOT);
        this.assetSymbol = assetSymbol.toUpperCase(Locale.ROOT);
        this.confirmNum = confirmNum;
        this.depositConfirmNum = depositConfirmNum;
        this.withdrawConfirmNum = withdrawConfirmNum;
        this.decimals = decimals;
        this.contractAddress = contractAddress == null ? "" : contractAddress;
        this.bip44CoinType = bip44CoinType;
    }

    private static RuntimeAsset nativeAsset(String chain, long confirmNum, long depositConfirmNum,
                                            long withdrawConfirmNum, int bip44CoinType) {
        return nativeAsset(chain, chain, confirmNum, depositConfirmNum, withdrawConfirmNum,
                bip44CoinType);
    }

    private static RuntimeAsset nativeAsset(String chain, String assetSymbol, long confirmNum,
                                            long depositConfirmNum, long withdrawConfirmNum, int bip44CoinType) {
        return new RuntimeAsset(null, chain, assetSymbol, confirmNum, depositConfirmNum, withdrawConfirmNum,
                null, "", bip44CoinType);
    }

    private static RuntimeAsset tokenAsset(String chain, String assetSymbol, long confirmNum,
                                           long depositConfirmNum, long withdrawConfirmNum, int bip44CoinType) {
        return new RuntimeAsset(null, chain, assetSymbol, confirmNum, depositConfirmNum, withdrawConfirmNum,
                null, "", bip44CoinType);
    }

    public static RuntimeAsset fromProfile(AccountChainProfile profile, ChainAsset asset) {
        return fromProfile(
                profile.getRuntimeCurrencyId(),
                profile.getChain(),
                profile.getNativeSymbol(),
                profile.getDepositConfirmations(),
                profile.getWithdrawConfirmations(),
                profile.getBip44CoinType(),
                asset);
    }

    public static RuntimeAsset fromProfile(BitcoinLikeChainProfile profile, ChainAsset asset) {
        return fromProfile(
                profile.getRuntimeCurrencyId(),
                profile.getChain(),
                profile.getNativeSymbol(),
                profile.getDepositConfirmations(),
                profile.getWithdrawConfirmations(),
                profile.getBip44CoinType(),
                asset);
    }

    private static RuntimeAsset fromProfile(Integer runtimeCurrencyId, String chain, String nativeSymbol,
                                            Integer depositConfirmations, Integer withdrawConfirmations,
                                            Integer bip44CoinType, ChainAsset asset) {
        int decimals = requireDecimals(chain, nativeSymbol, asset);
        String contractAddress = asset == null ? "" : asset.getContractAddress();
        return new RuntimeAsset(
                runtimeCurrencyId,
                chain,
                nativeSymbol,
                depositConfirmations,
                depositConfirmations,
                withdrawConfirmations,
                decimals,
                contractAddress,
                bip44CoinType);
    }

    public static RuntimeAsset fromToken(AccountChainProfile profile, TokenDefinition token) {
        Integer runtimeCurrencyId = token.getId() == null ? null : Math.toIntExact(token.getId());
        int decimals = requireDecimals(token.getChain(), token.getSymbol(), token.getDecimals());
        String contractAddress = requireContractAddress(token);
        return new RuntimeAsset(
                runtimeCurrencyId,
                profile.getChain(),
                token.getSymbol(),
                profile.getDepositConfirmations(),
                profile.getDepositConfirmations(),
                profile.getWithdrawConfirmations(),
                decimals,
                contractAddress,
                profile.getBip44CoinType());
    }

    public static RuntimeAsset fromTransaction(WithdrawTransaction transaction) {
        if (!StringUtils.hasText(transaction.getChain())
                || !StringUtils.hasText(transaction.getAssetSymbol())
                || transaction.getAssetDecimals() == null
                || transaction.getBip44CoinType() == null) {
            throw new UnsupportedCurrency(String.valueOf(transaction.getCurrency()));
        }
        return new RuntimeAsset(
                transaction.getCurrency(),
                transaction.getChain(),
                transaction.getAssetSymbol(),
                0,
                0,
                0,
                transaction.getAssetDecimals(),
                transaction.getContractAddress(),
                transaction.getBip44CoinType());
    }

    private static int requireDecimals(String chain, String symbol, ChainAsset asset) {
        if (asset == null) {
            throw new IllegalStateException("missing chain_asset for " + chain + "/" + symbol);
        }
        return requireDecimals(chain, symbol, asset.getDecimals());
    }

    private static int requireDecimals(String chain, String symbol, Integer decimals) {
        if (decimals == null) {
            throw new IllegalStateException("missing decimals in DB asset metadata for " + chain + "/" + symbol);
        }
        return decimals;
    }

    private static String requireContractAddress(TokenDefinition token) {
        if (!StringUtils.hasText(token.getContractAddress())) {
            throw new IllegalStateException(
                    "missing token contract address in DB asset metadata for "
                            + token.getChain() + "/" + token.getSymbol());
        }
        return token.getContractAddress();
    }

    public static boolean isErc20(RuntimeAsset asset) {
        return asset != null && StringUtils.hasText(asset.getContractAddress()) && "ETH".equals(asset.chain());
    }

    public static RuntimeAsset toMainCurrency(RuntimeAsset asset) {
        return isErc20(asset) ? ETH.withRuntimeCurrencyId(null) : asset;
    }

    public RuntimeAsset withRuntimeCurrencyId(Integer runtimeCurrencyId) {
        return new RuntimeAsset(runtimeCurrencyId, chain, assetSymbol, confirmNum, depositConfirmNum,
                withdrawConfirmNum, decimals, contractAddress, bip44CoinType);
    }

    public void applyTo(WithdrawTransaction transaction) {
        transaction.setCurrency(getIndex());
        transaction.setChain(chain);
        transaction.setAssetSymbol(assetSymbol);
        transaction.setAssetDecimals(getDecimals());
        transaction.setBip44CoinType(bip44CoinType);
        transaction.setContractAddress(contractAddress);
    }

    public boolean sameAsset(RuntimeAsset other) {
        return other != null
                && chain.equalsIgnoreCase(other.chain)
                && assetSymbol.equalsIgnoreCase(other.assetSymbol);
    }

    public boolean isChain(String expectedChain) {
        return chain.equalsIgnoreCase(expectedChain);
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
        return BigDecimal.TEN.pow(getDecimals());
    }

    public int getDecimals() {
        if (decimals == null) {
            throw new IllegalStateException("asset decimals must be loaded from DB metadata for " + this);
        }
        return decimals;
    }

    public int getIndex() {
        if (runtimeCurrencyId == null) {
            throw new IllegalStateException("runtime currency id must be loaded from chain_profile for " + this);
        }
        return runtimeCurrencyId;
    }

    public int getBip44CoinType() {
        return bip44CoinType;
    }

    public int getDerivationCoinType() {
        return ChainType.derivationCoinType(chain, bip44CoinType);
    }

    public String getName() {
        return assetSymbol.toLowerCase(Locale.ROOT);
    }

    public String getContractAddress() {
        return contractAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RuntimeAsset that)) {
            return false;
        }
        return chain.equals(that.chain) && assetSymbol.equals(that.assetSymbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chain, assetSymbol);
    }

    @Override
    public String toString() {
        return chain + ":" + assetSymbol;
    }
}
