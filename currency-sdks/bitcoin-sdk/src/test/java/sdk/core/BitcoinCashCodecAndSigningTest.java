package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.*;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BitcoinCashCodecAndSigningTest {
    @Test void cashAddrRoundTripAndForkIdSignaturesMustPass(){
        var params=BitcoinCashNetworkParameters.testnet();
        ECKey k1=ECKey.fromPrivate(BigInteger.TWO),k2=ECKey.fromPrivate(BigInteger.valueOf(3)),k3=ECKey.fromPrivate(BigInteger.valueOf(4));
        LegacyMultiSignAddressGenerator g=new LegacyMultiSignAddressGenerator();
        g.addECKey(k1);g.addECKey(k2);g.addECKey(k3);
        String legacy=g.generateAddress(params,2);
        String cash=BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromBase58(params,legacy),params.cashPrefix());
        assertTrue(cash.startsWith("bchtest:p"));
        assertEquals(legacy,BitcoinCashAddressCodec.toLegacy(params,params.cashPrefix(),cash).toBase58());
        String dest=BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromKey(params,ECKey.fromPrivate(BigInteger.valueOf(5))),params.cashPrefix());
        BitcoinCashMultisigTransactionBuilder firstBuilder=new BitcoinCashMultisigTransactionBuilder(params);
        firstBuilder.addInput("22".repeat(32),0,g.getRedeemScriptHex(),Coin.COIN);
        firstBuilder.addOutput(dest,Coin.valueOf(99_999_000L));
        String first=firstBuilder.buildFirstSign(List.of(k1));
        BitcoinCashMultisigTransactionBuilder b=new BitcoinCashMultisigTransactionBuilder(params);
        b.addInput("22".repeat(32),0,g.getRedeemScriptHex(),Coin.COIN);
        String raw=b.buildSecondSign(first,List.of(k2),List.of(g.getRedeemScriptHex()));
        assertFalse(raw.isBlank());
        assertFalse(b.getTransaction().hasWitnesses());
        var chunks=b.getTransaction().getInput(0).getScriptSig().chunks();
        assertEquals(0x41, chunks.get(1).data[chunks.get(1).data.length-1] & 0xff);
        assertEquals(0x41, chunks.get(2).data[chunks.get(2).data.length-1] & 0xff);
    }

    @Test void regtestCashAddrAndChecksumMustBeIndependent(){
        var params=BitcoinCashNetworkParameters.regtest();
        var key=ECKey.fromPrivate(BigInteger.valueOf(7));
        var legacy=LegacyAddress.fromKey(params,key);
        String cash=BitcoinCashAddressCodec.fromLegacy(legacy,params.cashPrefix());
        assertTrue(cash.startsWith("bchreg:q"));
        assertEquals(legacy.toBase58(),
                BitcoinCashAddressCodec.toLegacy(params,"bchreg",cash).toBase58());
        assertThrows(IllegalArgumentException.class,
                ()->BitcoinCashAddressCodec.decode("bchtest",cash));
        String corrupted=cash.substring(0,cash.length()-1)
                +(cash.endsWith("q")?"p":"q");
        assertThrows(IllegalArgumentException.class,
                ()->BitcoinCashAddressCodec.decode("bchreg",corrupted));

        String nodeAddress="bchreg:qq9zdapzet6wtf7yeyqeqsaz2x2tr4kvmq2xuvmgpv";
        var decoded=BitcoinCashAddressCodec.decode("bchreg",nodeAddress);
        assertEquals(nodeAddress,BitcoinCashAddressCodec.encode(
                "bchreg",decoded.type(),decoded.hash()));
        assertEquals(nodeAddress,BitcoinCashAddressCodec.encode(
                "bchreg",
                BitcoinCashAddressCodec.decode(
                        "bchreg",nodeAddress.substring("bchreg:".length())).type(),
                BitcoinCashAddressCodec.decode(
                        "bchreg",nodeAddress.substring("bchreg:".length())).hash()));
    }

    @Test void feePlanMustSupportWithdrawalChangeAndOneOutputCollection(){
        var withdrawal=BitcoinCashFeePolicy.calculateSpendPlan(
                1_000_000_000L,100_000_000L,1,1,1,546);
        assertEquals(377,withdrawal.fee());
        assertEquals(899_999_623L,withdrawal.change());
        assertEquals(377,withdrawal.estimatedBytes());

        var collection=BitcoinCashFeePolicy.calculateSpendPlan(
                500_000_000L,499_999_657L,1,1,1,546);
        assertEquals(343,collection.fee());
        assertEquals(0,collection.change());
        assertEquals(343,collection.estimatedBytes());
    }
}
