package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitecoinNetworkParamsTest {
    @Test
    void litecoinTestnetShouldUseIndependentAddressParameters() {
        LitecoinNetworkParameters params = LitecoinNetworkParameters.testnet();

        assertEquals(111, params.getAddressHeader());
        assertEquals(58, params.getP2SHHeader());
        assertEquals("tltc", params.getSegwitAddressHrp());
    }

    @Test
    void p2wshAddressShouldUseLitecoinBech32HrpAndNotParseAsBitcoinTestnet() {
        SegwitMultiSignAddressGenerator generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(ECKey.fromPrivate(BigInteger.valueOf(2), true));
        generator.addECKey(ECKey.fromPrivate(BigInteger.valueOf(3), true));
        generator.addECKey(ECKey.fromPrivate(BigInteger.valueOf(4), true));

        String address = generator.generateAddress(LitecoinNetworkParameters.testnet(), 2);

        assertTrue(address.startsWith("tltc1"));
        assertThrows(AddressFormatException.class, () -> Address.fromString(TestNet3Params.get(), address));
    }

    @Test
    void litecoinFeePolicyShouldClampAndKeepDustSeparateFromBtc() {
        assertEquals(1L, LitecoinFeePolicy.clampFeeRate(0));
        assertEquals(100L, LitecoinFeePolicy.clampFeeRate(10_000));
        assertEquals(2L, LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE);
        assertEquals(1_000L, LitecoinFeePolicy.DUST_THRESHOLD_LITOSHI);
    }

    @Test
    void transactionBuilderShouldAcceptLitecoinAddressAndRejectBitcoinAddress() {
        SegwitMultiSignAddressGenerator generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(ECKey.fromPrivate(BigInteger.valueOf(5), true));
        generator.addECKey(ECKey.fromPrivate(BigInteger.valueOf(6), true));
        generator.addECKey(ECKey.fromPrivate(BigInteger.valueOf(7), true));
        String ltcAddress = generator.generateAddress(LitecoinNetworkParameters.testnet(), 2);

        WitnessTransactionBuilder builder = new WitnessTransactionBuilder(LitecoinNetworkParameters.testnet());
        builder.addInput("0101010101010101010101010101010101010101010101010101010101010101",
                0, generator.getWitnessScriptStr(), Coin.valueOf(100_000L));
        builder.addOutput(ltcAddress, Coin.valueOf(50_000L));

        assertThrows(IllegalArgumentException.class,
                () -> builder.addOutput("tb1qsecond", Coin.valueOf(10_000L)));
    }
}
