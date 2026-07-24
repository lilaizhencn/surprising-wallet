package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.api.model.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoBackendClientTest {

    @Test
    void shouldTreatOnlyUnsuccessful404AsNotFound() {
        assertTrue(CardanoBackendClient.isNotFound(Result.error("not indexed yet").code(404)));
        assertFalse(CardanoBackendClient.isNotFound(Result.error("backend unavailable").code(503)));
        assertFalse(CardanoBackendClient.isNotFound(Result.success("ok").code(404)));
        assertFalse(CardanoBackendClient.isNotFound(null));
    }
}
