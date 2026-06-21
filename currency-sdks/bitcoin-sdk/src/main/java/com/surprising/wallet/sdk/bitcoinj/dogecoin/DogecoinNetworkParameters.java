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
 * Dogecoin parameters required for legacy address parsing and transaction
 * signing. Header/AuxPoW validation stays in Dogecoin Core; this wallet scans a
 * trusted Core-compatible RPC and does not run an SPV chain.
 */
public final class DogecoinNetworkParameters extends NetworkParameters {
    public static final String ID_MAINNET = DogecoinNetwork.MAINNET.id();
    public static final String ID_TESTNET = DogecoinNetwork.TESTNET.id();

    private static final DogecoinNetworkParameters MAINNET =
            new DogecoinNetworkParameters(DogecoinNetwork.MAINNET);
    private static final DogecoinNetworkParameters TESTNET =
            new DogecoinNetworkParameters(DogecoinNetwork.TESTNET);
    private static final Coin UNBOUNDED_MONEY = Coin.valueOf(Long.MAX_VALUE);

    private final DogecoinNetwork dogecoinNetwork;
    private final Block genesisBlock;

    private DogecoinNetworkParameters(DogecoinNetwork network) {
        super(network);
        this.dogecoinNetwork = network;
        this.addressHeader = network.legacyAddressHeader();
        this.p2shHeader = network.legacyP2SHHeader();
        this.segwitAddressHrp = "";
        this.interval = 240;
        this.targetTimespan = 4 * 60 * 60;
        this.maxTarget = new BigInteger(
                "00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        this.spendableCoinbaseDepth = 240;
        if (network == DogecoinNetwork.MAINNET) {
            this.dumpedPrivateKeyHeader = 158;
            this.port = 22556;
            this.packetMagic = 0xc0c0c0c0;
            this.bip32HeaderP2PKHpub = 0x02facafd;
            this.bip32HeaderP2PKHpriv = 0x02fac398;
            this.genesisBlock = Block.createGenesis(
                    Instant.ofEpochSecond(1386325540L), 0x1e0ffff0L, 99943L);
        } else {
            this.dumpedPrivateKeyHeader = 241;
            this.port = 44556;
            this.packetMagic = 0xfcc1b7dc;
            this.bip32HeaderP2PKHpub = 0x043587cf;
            this.bip32HeaderP2PKHpriv = 0x04358394;
            this.genesisBlock = Block.createGenesis(
                    Instant.ofEpochSecond(1391503289L), 0x1e0ffff0L, 997879L);
        }
        this.bip32HeaderP2WPKHpub = this.bip32HeaderP2PKHpub;
        this.bip32HeaderP2WPKHpriv = this.bip32HeaderP2PKHpriv;
    }

    public static DogecoinNetworkParameters mainnet() {
        return MAINNET;
    }

    public static DogecoinNetworkParameters testnet() {
        return TESTNET;
    }

    @Override
    public String getPaymentProtocolId() {
        return dogecoinNetwork.id();
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
            throws VerificationException, BlockStoreException {
        // Dogecoin Core validates Digishield/AuxPoW. Wallet-side SPV validation is out of scope.
    }

    @Override
    public Block getGenesisBlock() {
        return genesisBlock;
    }

    @Override
    public Coin getMaxMoney() {
        return UNBOUNDED_MONEY;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return MonetaryFormat.BTC.code(0, "DOGE");
    }

    @Override
    public String getUriScheme() {
        return dogecoinNetwork.uriScheme();
    }

    @Override
    public boolean hasMaxMoney() {
        return false;
    }

    @Override
    public BitcoinSerializer getSerializer() {
        return new BitcoinSerializer(this);
    }
}
