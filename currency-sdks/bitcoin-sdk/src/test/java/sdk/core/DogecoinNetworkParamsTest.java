package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DogecoinNetworkParamsTest {
    @Test
    void mainnetAndTestnetMustUseDogecoinCorePrefixes() {
        var mainnet = DogecoinNetworkParameters.mainnet();
        var testnet = DogecoinNetworkParameters.testnet();

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
