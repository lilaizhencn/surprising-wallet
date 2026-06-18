/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.surprising.wallet.sdk.bitcoinj.header;

import com.google.common.base.Stopwatch;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

/**
 * Parameters for the main production network on which people trade goods and services.
 */
public class LiteMainNetParam extends NetworkParameters {
    public static final String BITCOIN_SCHEME = "litecoin";
    private static final Logger log = LoggerFactory.getLogger(LiteMainNetParam.class);
    /**
     *
     */
    private static final long serialVersionUID = 6681030675778751621L;
    private static LiteMainNetParam instance;

    public LiteMainNetParam() {
        super();

        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        maxTarget = Utils.decodeCompactBits(0x1e0fffffL);
        addressHeader = 48;
        p2shHeader = 5;
//        acceptableAddressCodes = new int[] {addressHeader, p2shHeader};
        dumpedPrivateKeyHeader = 128;
        port = 9333;
        packetMagic = 0xfbc0b6db;

        /*
         * genesisBlock = createGenesis(this); genesisBlock.setDifficultyTarget(0x1e0ffff0L);
         * genesisBlock.setTime(1317972665L); genesisBlock.setNonce(2084524493L);
         * genesisBlock.setMerkleRoot(new
         * Sha256Hash("97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9"));
         *
         */
        id = ID_MAINNET;
        subsidyDecreaseBlockCount = 840000;
        spendableCoinbaseDepth = 100;
        // String genesisHash = genesisBlock.getHashAsString();
        // checkState(genesisHash.equals("12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2"),
        // genesisHash);

        // This contains (at a minimum) the blocks which are not BIP30 compliant. BIP30 changed how
        // duplicate
        // transactions are handled. Duplicated transactions could occur in the case where a
        // coinbase had the same
        // extraNonce and the same outputs but appeared at different heights, and greatly
        // complicated re-org handling.
        // Having these here simplifies block connection logic considerably.
        /*
         * checkpoints.put(91722, new
         * Sha256Hash("00000000000271a2dc26e7667f8419f2e15416dc6955e5a6c6cdf3f2574dd08e"));
         * checkpoints.put(91812, new
         * Sha256Hash("00000000000af0aed4792b1acee3d966af36cf5def14935db8de83d6f9306f2f"));
         * checkpoints.put(91842, new
         * Sha256Hash("00000000000a4d0a398161ffc163c503763b1f4360639393e0e4c8e300e0caec"));
         * checkpoints.put(91880, new
         * Sha256Hash("00000000000743f190a18c5577a3c2d2a1f610ae9601ac046a38084ccb7cd721"));
         * checkpoints.put(200000, new
         * Sha256Hash("000000000000034a7dedef4a161fa058a2d67a173a90155f3a2fe6fc132e0ebf"));
         */

        /*
         * dnsSeeds = new String[] { "dnsseed.litecointools.com", "dnsseed.litecoinpool.org",
         * "dnsseed.ltc.xurious.com", "dnsseed.koin-project.com", "dnsseed.weminemnc.com",
         * "dnsseed.jointsecurityarea.org", };
         */
    }

    public static synchronized LiteMainNetParam get() {
        if (instance == null) {
            instance = new LiteMainNetParam();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_MAINNET;
    }

    /**
     * Checks if we are at a difficulty transition point.
     *
     * @param storedPrev The previous stored block
     * @return If this is a difficulty transition point
     */
    protected boolean isDifficultyTransitionPoint(StoredBlock storedPrev) {
        return ((storedPrev.getHeight() + 1) % getInterval()) == 0;
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock,
                                           BlockStore blockStore) throws VerificationException, BlockStoreException {
        // TODO Auto-generated method stub

        Block prev = storedPrev.getHeader();

        // Is this supposed to be a difficulty transition point?
        if (!isDifficultyTransitionPoint(storedPrev)) {

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget()) {
                throw new VerificationException(
                        "Unexpected change in difficulty at height " + storedPrev.getHeight() + ": "
                                + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs "
                                + Long.toHexString(prev.getDifficultyTarget()));
            }
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it
        // only occurs every
        // two weeks after the initial block chain download.
        Stopwatch watch = Stopwatch.createStarted();
        StoredBlock cursor = blockStore.get(prev.getHash());
        for (int i = 0; i < getInterval() - 1; i++) {
            // This should never happen. If it does, it means we are following an incorrect or
            // busted chain.
            if (cursor == null) {
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }
        watch.stop();
        if (watch.elapsed(TimeUnit.MILLISECONDS) > 50) {
            log.info("Difficulty transition traversal took {}", watch);
        }

        Block blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        int targetTimespan = getTargetTimespan();
        if (timespan < targetTimespan / 4) {
            timespan = targetTimespan / 4;
        }
        if (timespan > targetTimespan * 4) {
            timespan = targetTimespan * 4;
        }

        BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
        newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

        if (newTarget.compareTo(getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
            newTarget = getMaxTarget();
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        long newTargetCompact = Utils.encodeCompactBits(newTarget);

        if (newTargetCompact != receivedTargetCompact) {
            throw new VerificationException(
                    "Network provided difficulty bits do not match what was calculated: "
                            + Long.toHexString(newTargetCompact) + " vs "
                            + Long.toHexString(receivedTargetCompact));
        }

    }

    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat();
    }

    @Override
    public String getUriScheme() {
        return BITCOIN_SCHEME;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }

    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain) {
        return new BitcoinSerializer(this, parseRetain);
    }

    @Override
    public int getProtocolVersionNum(ProtocolVersion version) {
        return version.getBitcoinProtocolVersion();
    }


    /*
     * private static Block createLiteGenesis(NetworkParameters n) { Block genesisBlock = new
     * Block(n); Transaction t = new Transaction(n); try { // A script containing the difficulty
     * bits and the following message: // //
     * "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks" byte[] bytes =
     * Hex.decode ("04b217bb4e022309"); t.addInput(new TransactionInput(n, t, bytes));
     * ByteArrayOutputStream scriptPubKeyBytes = new ByteArrayOutputStream();
     * Script.writeBytes(scriptPubKeyBytes, Hex.decode
     * ("41044870341873accab7600d65e204bb4ae47c43d20c562ebfbf70cbcb188da98dec8b5ccf0526c8e4d954c6b47b898cc30adf1ff77c2e518ddc9785b87ccb90b8cdac"
     * )); scriptPubKeyBytes.write(ScriptOpCodes.OP_CHECKSIG); t.addOutput(new TransactionOutput(n,
     * t, Utils.toNanoCoins(50, 0), scriptPubKeyBytes.toByteArray())); } catch (Exception e) { //
     * Cannot happen. throw new RuntimeException(e); } genesisBlock.addTransaction(t); return
     * genesisBlock; }
     */

}
