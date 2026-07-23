package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.service.CustodyPasswordService;

class CustodyPasswordServiceTest {
    private final CustodyPasswordService passwords = new CustodyPasswordService();

    @Test
    void hashesWithUniqueSaltAndVerifies() {
        String first = passwords.hash("a-strong-password-123");
        String second = passwords.hash("a-strong-password-123");

        assertNotEquals(first, second);
        assertTrue(passwords.verify("a-strong-password-123", first));
        assertFalse(passwords.verify("wrong-password-123", first));
        assertFalse(passwords.verify("a-strong-password-123", "invalid"));
    }

    @Test
    void rejectsWeakPassword() {
        assertThrows(IllegalArgumentException.class, () -> passwords.hash("too-short"));
    }
}
