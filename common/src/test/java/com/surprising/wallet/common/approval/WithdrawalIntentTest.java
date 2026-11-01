package com.surprising.wallet.common.approval;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class WithdrawalIntentTest {
    @Test void absentSubjectAndExactDecimalStringsSurvivePersistenceRoundTrip() {
        var json=new ObjectMapper();
        var intent=new WithdrawalIntent(UUID.randomUUID(),UUID.randomUUID(),"CW-roundtrip","ETH","sepolia","USDC","recipient",
                new BigDecimal("9007199254740993.123456789012345678901234"),new BigDecimal("0.01"),"API",UUID.randomUUID(),null, "external-test", "ADDRESS");
        byte[] encoded=json.writeValueAsBytes(intent);
        var restored=json.readValue(encoded,WithdrawalIntent.class);
        assertEquals(intent,restored);assertEquals("",restored.subject());assertEquals(intent.hash(json),restored.hash(json));
        assertTrue(new String(encoded,java.nio.charset.StandardCharsets.UTF_8).contains("\"amount\":\"9007199254740993.123456789012345678901234\""));
    }
    @Test void extremeExponentsAreRejectedBeforeScalingAndStoredNumericBoundsAreExact() {
        for(String value:java.util.List.of("1e1000000000","1e-1000000000","1e60","0.0000000000000000000000001"))
            assertThrows(IllegalArgumentException.class,()->WithdrawalIntent.number(new BigDecimal(value),true));
        assertEquals(new BigDecimal("1e59").setScale(0),WithdrawalIntent.number(new BigDecimal("1e59"),true));
        assertEquals(new BigDecimal("0.000000000000000000000001"),WithdrawalIntent.number(new BigDecimal("0.000000000000000000000001"),true));
        assertEquals(BigDecimal.ZERO,WithdrawalIntent.number(new BigDecimal("0e1000000000"),false));
    }
}
