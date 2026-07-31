package com.surprising.wallet.sdk.bitcoinj.dogecoin;

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
public final class DogecoinNetworkParameters extends NetworkParameters {
    /**
     * 定义 {@code ID_MAINNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String ID_MAINNET = DogecoinNetwork.MAINNET.id();
    /**
     * 定义 {@code ID_TESTNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String ID_TESTNET = DogecoinNetwork.TESTNET.id();
    /**
     * 定义 {@code ID_REGTEST} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String ID_REGTEST = DogecoinNetwork.REGTEST.id();

    /**
     * 定义 {@code MAINNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final DogecoinNetworkParameters MAINNET =
            new DogecoinNetworkParameters(DogecoinNetwork.MAINNET);
    /**
     * 定义 {@code TESTNET} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final DogecoinNetworkParameters TESTNET =
            new DogecoinNetworkParameters(DogecoinNetwork.TESTNET);
    /**
     * 定义 {@code REGTEST} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final DogecoinNetworkParameters REGTEST =
            new DogecoinNetworkParameters(DogecoinNetwork.REGTEST);
    /**
     * 定义 {@code UNBOUNDED_MONEY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final Coin UNBOUNDED_MONEY = Coin.valueOf(Long.MAX_VALUE);

    /**
     * 保存 {@code dogecoinNetwork}，表示链、网络、资产或代币配置。
     */
    private final DogecoinNetwork dogecoinNetwork;
    /**
     * 保存 {@code genesisBlock}，用于标识交易、区块或业务记录。
     */
    private final Block genesisBlock;

    /**
     * 构造 {@code DogecoinNetworkParameters}，初始化该组件运行所需的状态和依赖。
     */
    private DogecoinNetworkParameters(DogecoinNetwork network) {
        super(network);
        this.dogecoinNetwork = network;
        this.addressHeader = network.legacyAddressHeader();
        this.p2shHeader = network.legacyP2SHHeader();
        this.segwitAddressHrp = "";
        this.interval = 240;
        this.targetTimespan = 4 * 60 * 60;
        if (network == DogecoinNetwork.MAINNET) {
            this.maxTarget = new BigInteger(
                    "00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
            this.spendableCoinbaseDepth = 240;
            this.dumpedPrivateKeyHeader = 158;
            this.port = 22556;
            this.packetMagic = 0xc0c0c0c0;
            this.bip32HeaderP2PKHpub = 0x02facafd;
            this.bip32HeaderP2PKHpriv = 0x02fac398;
            this.genesisBlock = Block.createGenesis(
                    Instant.ofEpochSecond(1386325540L), 0x1e0ffff0L, 99943L);
        } else if (network == DogecoinNetwork.TESTNET) {
            this.maxTarget = new BigInteger(
                    "00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
            this.spendableCoinbaseDepth = 240;
            this.dumpedPrivateKeyHeader = 241;
            this.port = 44556;
            this.packetMagic = 0xfcc1b7dc;
            this.bip32HeaderP2PKHpub = 0x043587cf;
            this.bip32HeaderP2PKHpriv = 0x04358394;
            this.genesisBlock = Block.createGenesis(
                    Instant.ofEpochSecond(1391503289L), 0x1e0ffff0L, 997879L);
        } else {
            this.maxTarget = new BigInteger(
                    "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
            this.spendableCoinbaseDepth = 60;
            this.dumpedPrivateKeyHeader = 239;
            this.port = 18444;
            this.packetMagic = 0xfabfb5da;
            this.bip32HeaderP2PKHpub = 0x043587cf;
            this.bip32HeaderP2PKHpriv = 0x04358394;
            this.genesisBlock = Block.createGenesis(
                    Instant.ofEpochSecond(1296688602L), 0x207fffffL, 2L);
        }
        this.bip32HeaderP2WPKHpub = this.bip32HeaderP2PKHpub;
        this.bip32HeaderP2WPKHpriv = this.bip32HeaderP2PKHpriv;
    }

    /**
     * 执行 {@code mainnet} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static DogecoinNetworkParameters mainnet() {
        return MAINNET;
    }

    /**
     * 执行 {@code testnet} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static DogecoinNetworkParameters testnet() {
        return TESTNET;
    }

    /**
     * 执行 {@code regtest} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static DogecoinNetworkParameters regtest() {
        return REGTEST;
    }

    /**
     * 获取或查询 {@code getPaymentProtocolId} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getPaymentProtocolId() {
        return dogecoinNetwork.id();
    }

    /**
     * 校验 {@code checkDifficultyTransitions} 对应的前置条件，不满足时抛出明确异常。
     */
    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
            throws VerificationException, BlockStoreException {
        // Dogecoin Core validates Digishield/AuxPoW. Wallet-side SPV validation is out of scope.
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
        return UNBOUNDED_MONEY;
    }

    /**
     * 获取或查询 {@code getMonetaryFormat} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public MonetaryFormat getMonetaryFormat() {
        return MonetaryFormat.BTC.code(0, "DOGE");
    }

    /**
     * 获取或查询 {@code getUriScheme} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getUriScheme() {
        return dogecoinNetwork.uriScheme();
    }

    /**
     * 判断 {@code hasMaxMoney} 对应的条件是否成立，并返回明确的布尔结果。
     */
    @Override
    public boolean hasMaxMoney() {
        return false;
    }

    /**
     * 获取或查询 {@code getSerializer} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BitcoinSerializer getSerializer() {
        return new BitcoinSerializer(this);
    }
}
