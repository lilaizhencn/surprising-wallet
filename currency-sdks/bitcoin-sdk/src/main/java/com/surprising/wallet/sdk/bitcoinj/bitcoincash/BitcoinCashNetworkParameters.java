package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import java.math.BigInteger;
import java.time.Instant;

public final class BitcoinCashNetworkParameters extends NetworkParameters {
    private static final BitcoinCashNetworkParameters MAIN =
            new BitcoinCashNetworkParameters(BitcoinCashNetwork.MAINNET);
    private static final BitcoinCashNetworkParameters TEST =
            new BitcoinCashNetworkParameters(BitcoinCashNetwork.TESTNET);
    private final BitcoinCashNetwork bchNetwork;
    private final Block genesis;

    private BitcoinCashNetworkParameters(BitcoinCashNetwork network) {
        super(network);
        bchNetwork = network;
        addressHeader = network.legacyAddressHeader();
        p2shHeader = network.legacyP2SHHeader();
        dumpedPrivateKeyHeader = network == BitcoinCashNetwork.MAINNET ? 128 : 239;
        port = network == BitcoinCashNetwork.MAINNET ? 8333 : 18333;
        packetMagic = network == BitcoinCashNetwork.MAINNET ? 0xd9b4bef9 : 0x0709110b;
        interval = 2016;
        targetTimespan = 14 * 24 * 60 * 60;
        maxTarget = new BigInteger("00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        bip32HeaderP2PKHpub = network == BitcoinCashNetwork.MAINNET ? 0x0488b21e : 0x043587cf;
        bip32HeaderP2PKHpriv = network == BitcoinCashNetwork.MAINNET ? 0x0488ade4 : 0x04358394;
        bip32HeaderP2WPKHpub = bip32HeaderP2PKHpub;
        bip32HeaderP2WPKHpriv = bip32HeaderP2PKHpriv;
        genesis = Block.createGenesis(Instant.ofEpochSecond(
                network == BitcoinCashNetwork.MAINNET ? 1231006505L : 1296688602L),
                network == BitcoinCashNetwork.MAINNET ? 0x1d00ffffL : 0x1d00ffffL,
                network == BitcoinCashNetwork.MAINNET ? 2083236893L : 414098458L);
    }
    public static BitcoinCashNetworkParameters mainnet() { return MAIN; }
    public static BitcoinCashNetworkParameters testnet() { return TEST; }
    public String cashPrefix() { return bchNetwork.cashPrefix(); }
    @Override public String getPaymentProtocolId() { return bchNetwork.id(); }
    @Override public void checkDifficultyTransitions(StoredBlock prev, Block next, BlockStore store)
            throws VerificationException, BlockStoreException { }
    @Override public Block getGenesisBlock() { return genesis; }
    @Override public Coin getMaxMoney() { return Coin.COIN.multiply(21_000_000L); }
    @Override public MonetaryFormat getMonetaryFormat() { return MonetaryFormat.BTC.code(0, "BCH"); }
    @Override public String getUriScheme() { return "bitcoincash"; }
    @Override public boolean hasMaxMoney() { return true; }
    @Override public BitcoinSerializer getSerializer() { return new BitcoinSerializer(this); }
}
