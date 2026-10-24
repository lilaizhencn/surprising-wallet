package com.surprising.wallet.common.chain;


import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

public final class AssetRuntimeMetadata {
    private final Integer runtimeCurrencyId;
    private final String chain;
    private final String assetSymbol;
    private final long confirmNum;
    private final Integer decimals;
    private final String contractAddress;
    private final int bip44CoinType;

    private AssetRuntimeMetadata(Integer runtimeCurrencyId, String chain, String assetSymbol,
                                 long confirmNum, Integer decimals,
                                 String contractAddress, int bip44CoinType) {
        this.runtimeCurrencyId = runtimeCurrencyId;
        this.chain = chain.toUpperCase(Locale.ROOT);
        this.assetSymbol = assetSymbol.toUpperCase(Locale.ROOT);
        this.confirmNum = confirmNum;
        this.decimals = decimals;
        this.contractAddress = contractAddress == null ? "" : contractAddress;
        this.bip44CoinType = bip44CoinType;
    }

    public static AssetRuntimeMetadata fromProfile(Integer runtimeCurrencyId, String chain, String nativeSymbol,
                                                   Integer depositConfirmations, Integer bip44CoinType,
                                                   Integer decimals, String contractAddress) {
        return new AssetRuntimeMetadata(
                runtimeCurrencyId,
                chain,
                nativeSymbol,
                depositConfirmations,
                requireDecimals(chain, nativeSymbol, decimals),
                contractAddress,
                bip44CoinType);
    }

    public static AssetRuntimeMetadata fromToken(AccountChainProfile profile, TokenDefinition token) {
        Integer runtimeCurrencyId = token.getId() == null ? null : Math.toIntExact(token.getId());
        int decimals = requireDecimals(token.getChain(), token.getSymbol(), token.getDecimals());
        String contractAddress = requireContractAddress(token);
        return new AssetRuntimeMetadata(
                runtimeCurrencyId,
                profile.getChain(),
                token.getSymbol(),
                profile.getDepositConfirmations(),
                decimals,
                contractAddress,
                profile.getBip44CoinType());
    }

    private static int requireDecimals(String chain, String symbol, Integer decimals) {
        if (decimals == null) {
            throw new IllegalStateException("missing decimals in DB asset metadata for " + chain + "/" + symbol);
        }
        return decimals;
    }

    private static String requireContractAddress(TokenDefinition token) {
        if (!hasText(token.getContractAddress())) {
            throw new IllegalStateException(
                    "missing token contract address in DB asset metadata for "
                            + token.getChain() + "/" + token.getSymbol());
        }
        return token.getContractAddress();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
        if (!(o instanceof AssetRuntimeMetadata that)) {
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
