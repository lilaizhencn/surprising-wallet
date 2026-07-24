package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

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

    @Test
    void amountPlanckParsesStringJsonValues() throws Exception {
        assertEquals(new BigInteger("9997224699029"),
                PolkadotRuntimeClient.amountPlanck(new ObjectMapper().readTree("\"9997224699029\"")));
    }
}
