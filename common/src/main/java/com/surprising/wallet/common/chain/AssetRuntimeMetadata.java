package com.surprising.wallet.common.chain;

import com.surprising.wallet.common.pojo.WithdrawTransaction;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
public final class AssetRuntimeMetadata {
    /**
     * 保存 {@code runtimeCurrencyId}，表示链、网络、资产或代币配置。
     */
    private final Integer runtimeCurrencyId;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private final String chain;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private final String assetSymbol;
    /**
     * 保存 {@code confirmNum}，记录开关、处理状态、确认结果或重试信息。
     */
    private final long confirmNum;
    /**
     * 保存 {@code decimals}，表示金额、余额、手续费、Gas 或精度相关参数。
     */
    private final Integer decimals;
    /**
     * 保存 {@code contractAddress}，表示链、网络、资产或代币配置。
     */
    private final String contractAddress;
    /**
     * 保存 {@code bip44CoinType}，表示链、网络、资产或代币配置。
     */
    private final int bip44CoinType;

    /**
     * 构造 {@code AssetRuntimeMetadata}，初始化该组件运行所需的状态和依赖。
     */
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

    /**
     * 解析 {@code fromProfile} 对应的输入，并转换为当前业务模型。
     */
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

    /**
     * 解析 {@code fromToken} 对应的输入，并转换为当前业务模型。
     */
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

    /**
     * 解析 {@code fromTransaction} 对应的输入，并转换为当前业务模型。
     */
    public static AssetRuntimeMetadata fromTransaction(WithdrawTransaction transaction) {
        if (!hasText(transaction.getChain())
                || !hasText(transaction.getAssetSymbol())
                || transaction.getAssetDecimals() == null
                || transaction.getBip44CoinType() == null) {
            throw new IllegalArgumentException(
                    "Currency " + transaction.getCurrency() + " is not supported");
        }
        return new AssetRuntimeMetadata(
                transaction.getCurrency(),
                transaction.getChain(),
                transaction.getAssetSymbol(),
                0,
                transaction.getAssetDecimals(),
                transaction.getContractAddress(),
                transaction.getBip44CoinType());
    }

    /**
     * 校验 {@code requireDecimals} 对应的前置条件，不满足时抛出明确异常。
     */
    private static int requireDecimals(String chain, String symbol, Integer decimals) {
        if (decimals == null) {
            throw new IllegalStateException("missing decimals in DB asset metadata for " + chain + "/" + symbol);
        }
        return decimals;
    }

    /**
     * 校验 {@code requireContractAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    private static String requireContractAddress(TokenDefinition token) {
        if (!hasText(token.getContractAddress())) {
            throw new IllegalStateException(
                    "missing token contract address in DB asset metadata for "
                            + token.getChain() + "/" + token.getSymbol());
        }
        return token.getContractAddress();
    }

    /**
     * 判断 {@code hasText} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 设置或更新 {@code applyTo} 对应的状态，并保持相关业务字段一致。
     */
    public void applyTo(WithdrawTransaction transaction) {
        transaction.setCurrency(getIndex());
        transaction.setChain(chain);
        transaction.setAssetSymbol(assetSymbol);
        transaction.setAssetDecimals(getDecimals());
        transaction.setBip44CoinType(bip44CoinType);
        transaction.setContractAddress(contractAddress);
    }

    /**
     * 获取或查询 {@code chain} 对应的数据，并向调用方返回当前业务状态。
     */
    public String chain() {
        return chain;
    }

    /**
     * 获取或查询 {@code assetSymbol} 对应的数据，并向调用方返回当前业务状态。
     */
    public String assetSymbol() {
        return assetSymbol;
    }

    /**
     * 执行 {@code name} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String name() {
        return assetSymbol;
    }

    /**
     * 获取或查询 {@code getConfirmNum} 对应的数据，供调用方读取当前状态。
     */
    public long getConfirmNum() {
        return confirmNum;
    }

    /**
     * 获取或查询 {@code getDecimal} 对应的数据，供调用方读取当前状态。
     */
    public BigDecimal getDecimal() {
        return BigDecimal.TEN.pow(getDecimals());
    }

    /**
     * 获取或查询 {@code getDecimals} 对应的数据，供调用方读取当前状态。
     */
    public int getDecimals() {
        if (decimals == null) {
            throw new IllegalStateException("asset decimals must be loaded from DB metadata for " + this);
        }
        return decimals;
    }

    /**
     * 获取或查询 {@code getIndex} 对应的数据，供调用方读取当前状态。
     */
    public int getIndex() {
        if (runtimeCurrencyId == null) {
            throw new IllegalStateException("runtime currency id must be loaded from chain_profile for " + this);
        }
        return runtimeCurrencyId;
    }

    /**
     * 获取或查询 {@code getBip44CoinType} 对应的数据，供调用方读取当前状态。
     */
    public int getBip44CoinType() {
        return bip44CoinType;
    }

    /**
     * 获取或查询 {@code getDerivationCoinType} 对应的数据，供调用方读取当前状态。
     */
    public int getDerivationCoinType() {
        return ChainType.derivationCoinType(chain, bip44CoinType);
    }

    /**
     * 获取或查询 {@code getName} 对应的数据，供调用方读取当前状态。
     */
    public String getName() {
        return assetSymbol.toLowerCase(Locale.ROOT);
    }

    /**
     * 获取或查询 {@code getContractAddress} 对应的数据，供调用方读取当前状态。
     */
    public String getContractAddress() {
        return contractAddress;
    }

    /**
     * 比较当前对象与目标对象是否具有相同的业务含义。
     */
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

    /**
     * 根据对象的业务字段计算哈希值，保证与相等性判断一致。
     */
    @Override
    public int hashCode() {
        return Objects.hash(chain, assetSymbol);
    }

    /**
     * 将对象转换为便于日志记录和排障的字符串表示。
     */
    @Override
    public String toString() {
        return chain + ":" + assetSymbol;
    }
}
