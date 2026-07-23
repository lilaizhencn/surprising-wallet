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
 * Litecoin parameters needed for wallet-side address parsing and raw transaction
 * signing. Header verification is deliberately out of scope; production scanning
 * uses a trusted Litecoin Core-compatible RPC endpoint.
 */
public final class LitecoinNetworkParameters extends NetworkParameters {
    public static final String ID_MAINNET = LitecoinNetwork.MAINNET.id();
    public static final String ID_TESTNET = LitecoinNetwork.TESTNET.id();
    public static final long MAINNET_DUST_THRESHOLD_LITOSHI = 1_000L;
    public static final long TESTNET_DUST_THRESHOLD_LITOSHI = 1_000L;

    private static final LitecoinNetworkParameters MAINNET = new LitecoinNetworkParameters(LitecoinNetwork.MAINNET);
    private static final LitecoinNetworkParameters TESTNET = new LitecoinNetworkParameters(LitecoinNetwork.TESTNET);
    private static final Coin MAX_MONEY = Coin.valueOf(84_000_000L * 100_000_000L);

    private final LitecoinNetwork litecoinNetwork;
    private final Block genesisBlock;

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

    public static LitecoinNetworkParameters mainnet() {
        return MAINNET;
    }

    public static LitecoinNetworkParameters testnet() {
        return TESTNET;
    }

    @Override
    public String getPaymentProtocolId() {
        return litecoinNetwork.id();
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
            throws VerificationException, BlockStoreException {
        // Wallet-side Litecoin integration relies on RPC block data and does not
        // perform SPV header validation in-process.
    }

    @Override
    public Block getGenesisBlock() {
        return genesisBlock;
    }

    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return MonetaryFormat.BTC.code(0, "LTC");
    }

    @Override
    public String getUriScheme() {
        return litecoinNetwork.uriScheme();
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }

    @Override
    public BitcoinSerializer getSerializer() {
        return new BitcoinSerializer(this);
    }
}
