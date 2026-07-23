package com.surprising.wallet.service.chain.sui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuiPtbTransactionBuilderTest {
    @Test
    void matchesSuiCliTransactionDataBcs() {
        String sender = "0xb1dd7ca2c37b738349ee47736224d840f61a00eefd88b686cfd2245af8865f51";
        String recipient = "0x4740d23e696837e19d7cdcacf780d446dbd64d2083d0b9fc0e20d30dac494e40";
        SuiRpcClient.SuiCoin gas = new SuiRpcClient.SuiCoin(
                "0x092cefbe8c5ee7b2ba23000fa9197e3aae34c9ee17fdd220f473c36d164b362e",
                "3", "55wJucoQLEgJ7ykgWDYR27dy62p2KNSoN9VBNTyoZELi", new BigDecimal("29997999987122120"));

        String actual = new SuiPtbTransactionBuilder().buildSuiTransfer(
                sender, List.of(gas), recipient, 100_000_000L, 1_000L, 10_000_000L);

        assertEquals("AAACACBHQNI+aWg34Z183Kz3gNRG29ZNIIPQufwOINMNrElOQAAIAOH1BQAAAAACAgABAQEAAQECAAABAACx3Xyiw3tzg0nuR3NiJNhA9hoA7v2ItobP0iRa+IZfUQEJLO++jF7nsrojAA+pGX46rjTJ7hf90iD0c8NtFks2LgMAAAAAAAAAIDyyemCCtteUtCaQHLbe/1Vj7oM/NHyOGNoEl1WarHErsd18osN7c4NJ7kdzYiTYQPYaAO79iLaGz9IkWviGX1HoAwAAAAAAAICWmAAAAAAAAA==",
                actual);
    }
}
