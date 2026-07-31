package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Bitcoin Cash网络参数配置类。定义Bitcoin Cash主网、测试网和回归测试网的网络级参数，
 * 包括地址头（addressHeader）、P2SH头（p2shHeader）、导出私钥头（dumpedPrivateKeyHeader）、
 * 端口、魔术字（packetMagic）、难度调整间隔（interval）、目标时间跨度（targetTimespan）、
 * 最大难度目标（maxTarget）以及BIP32扩展密钥头（bip32Header）等关键网络参数。
 */
public final class BitcoinCashNetworkParameters extends NetworkParameters {
    /**
     * 定义 {@code MAIN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BitcoinCashNetworkParameters MAIN =
            new BitcoinCashNetworkParameters(BitcoinCashNetwork.MAINNET);
    /**
     * 定义 {@code TEST} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BitcoinCashNetworkParameters TEST =
            new BitcoinCashNetworkParameters(BitcoinCashNetwork.TESTNET);
    /**
     * 定义 {@code REGTEST} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BitcoinCashNetworkParameters REGTEST =
            new BitcoinCashNetworkParameters(BitcoinCashNetwork.REGTEST);
    /**
     * 保存 {@code bchNetwork}，表示链、网络、资产或代币配置。
     */
    private final BitcoinCashNetwork bchNetwork;
    /**
     * 保存 {@code genesis}，用于承载当前对象的运行配置或业务数据。
     */
    private final Block genesis;

    /**
     * 构造 {@code BitcoinCashNetworkParameters}，初始化该组件运行所需的状态和依赖。
     */
    private BitcoinCashNetworkParameters(BitcoinCashNetwork network) {
        super(network);
        bchNetwork = network;
        addressHeader = network.legacyAddressHeader();
        p2shHeader = network.legacyP2SHHeader();
        dumpedPrivateKeyHeader = network == BitcoinCashNetwork.MAINNET ? 128 : 239;
        port = network == BitcoinCashNetwork.MAINNET ? 8333
                : network == BitcoinCashNetwork.REGTEST ? 18444 : 18333;
        packetMagic = network == BitcoinCashNetwork.MAINNET ? 0xd9b4bef9
                : network == BitcoinCashNetwork.REGTEST ? 0xfabfb5da : 0x0709110b;
        interval = 2016;
        targetTimespan = 14 * 24 * 60 * 60;
        maxTarget = new BigInteger(
                network == BitcoinCashNetwork.REGTEST
                        ? "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                        : "00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                16);
        bip32HeaderP2PKHpub = network == BitcoinCashNetwork.MAINNET ? 0x0488b21e : 0x043587cf;
        bip32HeaderP2PKHpriv = network == BitcoinCashNetwork.MAINNET ? 0x0488ade4 : 0x04358394;
        bip32HeaderP2WPKHpub = bip32HeaderP2PKHpub;
        bip32HeaderP2WPKHpriv = bip32HeaderP2PKHpriv;
        genesis = network == BitcoinCashNetwork.REGTEST
                ? Block.createGenesis(Instant.ofEpochSecond(1296688602L), 0x207fffffL, 2L)
                : Block.createGenesis(
                        Instant.ofEpochSecond(
                                network == BitcoinCashNetwork.MAINNET ? 1231006505L : 1296688602L),
                        0x1d00ffffL,
                        network == BitcoinCashNetwork.MAINNET ? 2083236893L : 414098458L);
    }
    /**
     * 执行 {@code mainnet} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static BitcoinCashNetworkParameters mainnet() { return MAIN; }
    /**
     * 执行 {@code testnet} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static BitcoinCashNetworkParameters testnet() { return TEST; }
    /**
     * 执行 {@code regtest} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static BitcoinCashNetworkParameters regtest() { return REGTEST; }
    /**
     * 执行 {@code cashPrefix} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String cashPrefix() { return bchNetwork.cashPrefix(); }
    /** 返回 BCH 网络对应的支付协议标识。 */
    @Override public String getPaymentProtocolId() { return bchNetwork.id(); }

    /** BCH 参数不执行 bitcoinj 默认的难度切换校验。 */
    @Override public void checkDifficultyTransitions(StoredBlock prev, Block next, BlockStore store)
            throws VerificationException, BlockStoreException { }

    /** 返回 BCH 的创世区块。 */
    @Override public Block getGenesisBlock() { return genesis; }

    /** 返回 BCH 的最大货币量。 */
    @Override public Coin getMaxMoney() { return Coin.COIN.multiply(21_000_000L); }

    /** 返回 BCH 使用的货币格式。 */
    @Override public MonetaryFormat getMonetaryFormat() { return MonetaryFormat.BTC.code(0, "BCH"); }

    /** 返回 BCH URI 使用的 scheme。 */
    @Override public String getUriScheme() { return "bitcoincash"; }

    /** BCH 使用固定的总量上限。 */
    @Override public boolean hasMaxMoney() { return true; }

    /** 创建 BCH 专用的交易序列化器。 */
    @Override public BitcoinSerializer getSerializer() { return new BitcoinSerializer(this); }
}
