package com.surprising.wallet.service.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolkadotRuntimeClientTest {
    @Test
    void mainnetUsesPolkadotSs58Prefix() {
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("DOT")
                .network("mainnet")
                .build();

        assertEquals(0, PolkadotRuntimeClient.ss58Prefix(profile));
    }

    @Test
    void westendUsesSubstrateSs58Prefix() {
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("DOT")
                .network("westend")
                .chainId(42L)
                .build();

        assertEquals(42, PolkadotRuntimeClient.ss58Prefix(profile));
    }
}
