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
        BitcoinCashMultisigTransactionBuilder b=new BitcoinCashMultisigTransactionBuilder(params);
        b.addInput("22".repeat(32),0,g.getRedeemScriptHex(),Coin.COIN);
        b.addOutput(dest,Coin.valueOf(99_999_000L));
        String first=b.buildFirstSign(List.of(k1));
        String raw=b.buildSecondSign(first,List.of(k2),List.of(g.getRedeemScriptHex()));
        assertFalse(raw.isBlank());
        assertFalse(b.getTransaction().hasWitnesses());
        var chunks=b.getTransaction().getInput(0).getScriptSig().chunks();
        assertEquals(0x41, chunks.get(1).data[chunks.get(1).data.length-1] & 0xff);
        assertEquals(0x41, chunks.get(2).data[chunks.get(2).data.length-1] & 0xff);
    }
}
