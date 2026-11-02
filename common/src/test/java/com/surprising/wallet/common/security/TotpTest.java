package com.surprising.wallet.common.security;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test void matchesRfc6238Sha1VectorsWithSixDigitTruncation() {
        long[] times = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] codes = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int i = 0; i < times.length; i++) assertEquals(codes[i], Totp.atStep(RFC_SECRET, times[i] / 30));
    }
    @Test void acceptsOneStepDriftButRejectsReplayAndMalformedCodes() {
        Instant time = Instant.ofEpochSecond(1234567890);
        long step = time.getEpochSecond() / 30;
        String code = Totp.atStep(RFC_SECRET, step);
        assertEquals(step, Totp.verify(RFC_SECRET, code, time, -1).orElseThrow());
        assertTrue(Totp.verify(RFC_SECRET, code, time, step).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, code, time.plusSeconds(90), -1).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, "00000x", time, -1).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, null, time, -1).isEmpty());
    }
    @Test void createsDistinctSecretsAndLocallyRenderableProvisioningUris() {
        String secret = Totp.newSecret();
        assertTrue(secret.matches("[A-Z2-7]{32}"));
        assertNotEquals(secret, Totp.newSecret());
        String uri = Totp.uri("Surprising Wallet", "reviewer@example.test", secret);
        assertTrue(uri.startsWith("otpauth://totp/Surprising%20Wallet%3Areviewer%40example.test?secret="));
        assertTrue(uri.contains("issuer=Surprising%20Wallet"));
        assertTrue(uri.endsWith("algorithm=SHA1&digits=6&period=30"));
    }
}
