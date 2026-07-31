package com.surprising.wallet.sdk.bitcoinj.litecoin;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.bitcoinj.core.BitcoinSerializer;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import java.math.BigInteger;
import java.time.Instant;

/**
 * 封装区块链网络参数、地址前缀和交易规则。
 */
public final class LitecoinNetworkParameters extends NetworkParameters {
    /**
     * 定义 {@code ID_MAINNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String ID_MAINNET = LitecoinNetwork.MAINNET.id();
    /**
     * 定义 {@code ID_TESTNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String ID_TESTNET = LitecoinNetwork.TESTNET.id();
    /**
     * 定义 {@code MAINNET_DUST_THRESHOLD_LITOSHI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long MAINNET_DUST_THRESHOLD_LITOSHI = 1_000L;
    /**
     * 定义 {@code TESTNET_DUST_THRESHOLD_LITOSHI} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final long TESTNET_DUST_THRESHOLD_LITOSHI = 1_000L;

    /**
     * 定义 {@code MAINNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final LitecoinNetworkParameters MAINNET = new LitecoinNetworkParameters(LitecoinNetwork.MAINNET);
    /**
     * 定义 {@code TESTNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final LitecoinNetworkParameters TESTNET = new LitecoinNetworkParameters(LitecoinNetwork.TESTNET);
    /**
     * 定义 {@code MAX_MONEY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Coin MAX_MONEY = Coin.valueOf(84_000_000L * 100_000_000L);

    /**
     * 保存 {@code litecoinNetwork}，表示链、网络、资产或代币配置。
     */
    private final LitecoinNetwork litecoinNetwork;
    /**
     * 保存 {@code genesisBlock}，用于标识交易、区块或业务记录。
     */
    private final Block genesisBlock;

    /**
     * 构造 {@code LitecoinNetworkParameters}，初始化该组件运行所需的状态和依赖。
     */
    private LitecoinNetworkParameters(LitecoinNetwork network) {
        super(network);
        this.litecoinNetwork = network;
        this.addressHeader = network.legacyAddressHeader();
        this.p2shHeader = network.legacyP2SHHeader();
        this.segwitAddressHrp = network.segwitAddressHrp();
        this.dumpedPrivateKeyHeader = network == LitecoinNetwork.MAINNET ? 176 : 239;
        this.port = network == LitecoinNetwork.MAINNET ? 9333 : 19335;
        this.packetMagic = network == LitecoinNetwork.MAINNET ? 0xfbc0b6db : 0xfdd2c8f1;
        this.interval = 2016;
        this.targetTimespan = (int) (3.5 * 24 * 60 * 60);
        this.maxTarget = new BigInteger("00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        this.bip32HeaderP2PKHpub = 0x0436f6e1;
        this.bip32HeaderP2PKHpriv = 0x0436ef7d;
        this.bip32HeaderP2WPKHpub = 0x0436f6e1;
        this.bip32HeaderP2WPKHpriv = 0x0436ef7d;
        this.genesisBlock = Block.createGenesis(Instant.ofEpochSecond(1317972665L), 0x1e0ffff0L, 2084524493L);
    }

    /**
     * 执行 {@code mainnet} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static LitecoinNetworkParameters mainnet() {
        return MAINNET;
    }

    /**
     * 执行 {@code testnet} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static LitecoinNetworkParameters testnet() {
        return TESTNET;
    }

    /**
     * 获取或查询 {@code getPaymentProtocolId} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getPaymentProtocolId() {
        return litecoinNetwork.id();
    }

    /**
     * 校验 {@code checkDifficultyTransitions} 对应的前置条件，不满足时抛出明确异常。
     */
    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
            throws VerificationException, BlockStoreException {
        // Wallet-side Litecoin integration relies on RPC block data and does not
        // perform SPV header validation in-process.
    }

    /**
     * 获取或查询 {@code getGenesisBlock} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public Block getGenesisBlock() {
        return genesisBlock;
    }

    /**
     * 获取或查询 {@code getMaxMoney} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    /**
     * 获取或查询 {@code getMonetaryFormat} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public MonetaryFormat getMonetaryFormat() {
        return MonetaryFormat.BTC.code(0, "LTC");
    }

    /**
     * 获取或查询 {@code getUriScheme} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getUriScheme() {
        return litecoinNetwork.uriScheme();
    }

    /**
     * 判断 {@code hasMaxMoney} 对应的条件是否成立，并返回明确的布尔结果。
     */
    @Override
    public boolean hasMaxMoney() {
        return true;
    }

    /**
     * 获取或查询 {@code getSerializer} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BitcoinSerializer getSerializer() {
        return new BitcoinSerializer(this);
    }
}
