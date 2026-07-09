package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DogecoinNetworkParamsTest {
    @Test
    void mainnetAndTestnetMustUseDogecoinCorePrefixes() {
        var mainnet = DogecoinNetworkParameters.mainnet();
        var testnet = DogecoinNetworkParameters.testnet();
        var regtest = DogecoinNetworkParameters.regtest();

        assertEquals(30, mainnet.getAddressHeader());
        assertEquals(22, mainnet.getP2SHHeader());
        assertEquals(158, mainnet.getDumpedPrivateKeyHeader());
        assertEquals(22556, mainnet.getPort());
        assertEquals(0xc0c0c0c0, mainnet.getPacketMagic());

        assertEquals(113, testnet.getAddressHeader());
        assertEquals(196, testnet.getP2SHHeader());
        assertEquals(241, testnet.getDumpedPrivateKeyHeader());
        assertEquals(44556, testnet.getPort());
        assertEquals(0xfcc1b7dc, testnet.getPacketMagic());
        assertFalse(testnet.hasMaxMoney());

        assertEquals(111, regtest.getAddressHeader());
        assertEquals(196, regtest.getP2SHHeader());
        assertEquals(239, regtest.getDumpedPrivateKeyHeader());
        assertEquals(18444, regtest.getPort());
        assertEquals(0xfabfb5da, regtest.getPacketMagic());
        assertEquals(60, regtest.getSpendableCoinbaseDepth());
    }

    @Test
    void regtestP2pkhMustNotBeParsedAsDogecoinTestnet() {
        String address = LegacyAddress.fromPubKeyHash(
                DogecoinNetworkParameters.regtest(), new byte[20]).toString();
        assertEquals(111, LegacyAddress.fromBase58(
                DogecoinNetworkParameters.regtest(), address).getVersion());
        assertThrows(AddressFormatException.WrongNetwork.class, () ->
                LegacyAddress.fromBase58(DogecoinNetworkParameters.testnet(), address));
    }

    @Test
    void feeAndDustMustFollowDogecoinRecommendation() {
        assertEquals(1_000_000L, DogecoinFeePolicy.RECOMMENDED_FEE_KOINU_PER_KB);
        assertEquals(1_000L, DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE);
        assertEquals(100_000L, DogecoinFeePolicy.HARD_DUST_THRESHOLD_KOINU);
        assertEquals(1_000_000L, DogecoinFeePolicy.RECOMMENDED_DUST_THRESHOLD_KOINU);
        assertEquals(377_000L, DogecoinFeePolicy.feeForBytes(377, 1_000));
    }
}
