package com.surprising.wallet.common.chain;

import java.util.Locale;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public enum ChainType {
    /**
     * 定义 {@code BTC} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    BTC("bitcoin", "utxo"),
    /**
     * 定义 {@code LTC} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    LTC("litecoin", "utxo"),
    /**
     * 定义 {@code DOGE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    DOGE("dogecoin", "utxo"),
    /**
     * 定义 {@code BCH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    BCH("bitcoin-cash", "utxo"),
    /**
     * 定义 {@code ETH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ETH("evm", "account"),
    /**
     * 定义 {@code BNB} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    BNB("evm", "account"),
    /**
     * 定义 {@code POLYGON} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    POLYGON("evm", "account"),
    /**
     * 定义 {@code ARBITRUM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ARBITRUM("evm", "account"),
    /**
     * 定义 {@code OPTIMISM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    OPTIMISM("evm", "account"),
    /**
     * 定义 {@code BASE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    BASE("evm", "account"),
    /**
     * 定义 {@code AVAX_C} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    AVAX_C("evm", "account"),
    /**
     * 定义 {@code HYPEREVM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    HYPEREVM("evm", "account"),
    /**
     * 定义 {@code MANTLE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    MANTLE("evm", "account"),
    /**
     * 定义 {@code LINEA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    LINEA("evm", "account"),
    /**
     * 定义 {@code SCROLL} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SCROLL("evm", "account"),
    /**
     * 定义 {@code UNICHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    UNICHAIN("evm", "account"),
    /**
     * 定义 {@code BERACHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    BERACHAIN("evm", "account"),
    /**
     * 定义 {@code GNOSIS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    GNOSIS("evm", "account"),
    /**
     * 定义 {@code CELO} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    CELO("evm", "account"),
    /**
     * 定义 {@code MONAD} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    MONAD("evm", "account"),
    /**
     * 定义 {@code WORLD_CHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    WORLD_CHAIN("evm", "account"),
    /**
     * 定义 {@code INK} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    INK("evm", "account"),
    /**
     * 定义 {@code TAIKO} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    TAIKO("evm", "account"),
    /**
     * 定义 {@code SONEIUM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SONEIUM("evm", "account"),
    /**
     * 定义 {@code MODE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    MODE("evm", "account"),
    /**
     * 定义 {@code LISK} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    LISK("evm", "account"),
    /**
     * 定义 {@code KATANA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    KATANA("evm", "account"),
    /**
     * 定义 {@code MEGAETH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    MEGAETH("evm", "account"),
    /**
     * 定义 {@code X_LAYER} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    X_LAYER("evm", "account"),
    /**
     * 定义 {@code DEGEN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    DEGEN("evm", "account"),
    /**
     * 定义 {@code ROBINHOOD_CHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ROBINHOOD_CHAIN("evm", "account"),
    /**
     * 定义 {@code ETHERLINK} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ETHERLINK("evm", "account"),
    /**
     * 定义 {@code IOTA_EVM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    IOTA_EVM("evm", "account"),
    /**
     * 定义 {@code OASIS_EMERALD} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    OASIS_EMERALD("evm", "account"),
    /**
     * 定义 {@code CRONOS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    CRONOS("evm", "account"),
    /**
     * 定义 {@code SONIC} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SONIC("evm", "account"),
    /**
     * 定义 {@code PULSECHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    PULSECHAIN("evm", "account"),
    /**
     * 定义 {@code ZETACHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ZETACHAIN("evm", "account"),
    /**
     * 定义 {@code CORE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    CORE("evm", "account"),
    /**
     * 定义 {@code SOMNIA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SOMNIA("evm", "account"),
    /**
     * 定义 {@code RONIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    RONIN("evm", "account"),
    /**
     * 定义 {@code CHILIZ} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    CHILIZ("evm", "account"),
    /**
     * 定义 {@code IOTEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    IOTEX("evm", "account"),
    /**
     * 定义 {@code KAIA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    KAIA("evm", "account"),
    /**
     * 定义 {@code PLASMA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    PLASMA("evm", "account"),
    /**
     * 定义 {@code STORY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    STORY("evm", "account"),
    /**
     * 定义 {@code SEI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SEI("evm", "account"),
    /**
     * 定义 {@code CONFLUX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    CONFLUX("evm", "account"),
    /**
     * 定义 {@code VECTOR_SMART_CHAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    VECTOR_SMART_CHAIN("evm", "account"),
    /**
     * 定义 {@code KROWN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    KROWN("evm", "account"),
    /**
     * 定义 {@code HYPERCORE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    HYPERCORE("hypercore", "account"),
    /**
     * 定义 {@code TRON} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    TRON("tron", "account"),
    /**
     * 定义 {@code XRP} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    XRP("xrp", "account"),
    /**
     * 定义 {@code SOLANA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SOLANA("solana", "account"),
    /**
     * 定义 {@code TON} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    TON("ton", "account"),
    /**
     * 定义 {@code APTOS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    APTOS("aptos", "account"),
    /**
     * 定义 {@code SUI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    SUI("sui", "object"),
    /**
     * 定义 {@code ADA} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    ADA("cardano", "utxo"),
    /**
     * 定义 {@code DOT} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    DOT("polkadot", "account"),
    /**
     * 定义 {@code NEAR} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    NEAR("near", "account"),
    /**
     * 定义 {@code XMR} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    XMR("monero", "privacy");

    /**
     * 定义 {@code EVM_SHARED_BIP44_COIN_TYPE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final int EVM_SHARED_BIP44_COIN_TYPE = 60;

    /**
     * 保存 {@code family}，表示链、网络、资产或代币配置。
     */
    private final String family;
    /**
     * 保存 {@code model}，用于承载当前对象的运行配置或业务数据。
     */
    private final String model;

    /**
     * 构造 {@code ChainType}，初始化该组件运行所需的状态和依赖。
     */
    ChainType(String family, String model) {
        this.family = family;
        this.model = model;
    }

    /**
     * 获取或查询 {@code getFamily} 对应的数据，供调用方读取当前状态。
     */
    public String getFamily() {
        return family;
    }

    /**
     * 获取或查询 {@code getModel} 对应的数据，供调用方读取当前状态。
     */
    public String getModel() {
        return model;
    }

    /**
     * 判断 {@code isEvm} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm() {
        return "evm".equals(family);
    }

    /**
     * 判断 {@code isUtxo} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isUtxo() {
        return "utxo".equals(model);
    }

    /**
     * 执行 {@code derivationCoinType} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static int derivationCoinType(String chain, int configuredCoinType) {
        try {
            return ChainType.valueOf(chain.toUpperCase(Locale.ROOT)).isEvm()
                    ? EVM_SHARED_BIP44_COIN_TYPE
                    : configuredCoinType;
        } catch (RuntimeException e) {
            return configuredCoinType;
        }
    }
}
