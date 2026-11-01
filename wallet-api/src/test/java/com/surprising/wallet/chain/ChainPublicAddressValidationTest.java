package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Address validation must remain independent of wallet-private key services. */
class ChainPublicAddressValidationTest {
    private final ChainAddressRuntime runtime = new ChainAddressRuntime();
    private final byte[] publicKey = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");

    @Test void cardanoPreservesEnterpriseNetworkHeaderAndChecksumValidation() {
        for (boolean mainnet : new boolean[]{true,false}) {
            String address = MpcCardanoTransactionCodec.address(publicKey,mainnet);
            assertTrue(runtime.checkAddress(ChainType.ADA,address));
            assertTrue(runtime.checkAddress(ChainType.ADA," " + address + " "));
            String changed = address.substring(0,address.length()-1)+(address.endsWith("q")?"p":"q");
            assertFalse(runtime.checkAddress(ChainType.ADA,changed));
        }
        assertFalse(runtime.checkAddress(ChainType.ADA,"addr_test1invalid"));
    }
    @Test void polkadotValidatesEachPublicPrefixAndChecksum() {
        for (int prefix : new int[]{0,2,42,1000}) {
            String address = MpcPolkadotTransactionCodec.address(publicKey,prefix);
            assertTrue(runtime.checkAddress(ChainType.DOT,address));
            String changed = address.substring(0,address.length()-1)+(address.endsWith("1")?"2":"1");
            assertFalse(runtime.checkAddress(ChainType.DOT,changed));
        }
        assertFalse(runtime.checkAddress(ChainType.DOT,"not-an-address"));
    }
    @Test void nearAcceptsPublicImplicitAndNamedAccountsWithoutDerivingASecret() {
        assertTrue(runtime.checkAddress(ChainType.NEAR,MpcNearTransactionCodec.address(publicKey)));
        assertTrue(runtime.checkAddress(ChainType.NEAR,"alice.near"));
        assertTrue(runtime.checkAddress(ChainType.NEAR,"alice-bob.testnet"));
        for (String invalid : new String[]{"Alice.near","alice..near","a","a".repeat(65),".alice.near"})
            assertFalse(runtime.checkAddress(ChainType.NEAR,invalid));
    }
}
