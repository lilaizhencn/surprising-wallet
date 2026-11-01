package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtxoSigningRequestTest {
    @Test void nativePrecisionIsBoundedBeforeAnyCallerDerivedPowerOrAmountArithmetic() {
        for(String chain:List.of("BTC","LTC","DOGE","BCH")) {
            assertDoesNotThrow(()->new UtxoSigningRequest.Asset(chain,BitcoinLikeNetwork.REGTEST,1,8,0));
            for(int precision:List.of(-1,0,6,18,Integer.MAX_VALUE))
                assertThrows(IllegalArgumentException.class,()->new UtxoSigningRequest.Asset(chain,BitcoinLikeNetwork.REGTEST,1,precision,0));
        }
    }
}
