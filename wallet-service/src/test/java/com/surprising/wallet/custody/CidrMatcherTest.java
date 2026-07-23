package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.model.CidrMatcher;

class CidrMatcherTest {
    @Test
    void matchesIpv4AndIpv6Networks() {
        assertTrue(CidrMatcher.matches("203.0.113.0/24", "203.0.113.42"));
        assertFalse(CidrMatcher.matches("203.0.113.0/24", "203.0.114.42"));
        assertTrue(CidrMatcher.matches("2001:db8::/32", "2001:db8:12::1"));
        assertFalse(CidrMatcher.matches("2001:db8::/48", "2001:db9::1"));
    }

    @Test
    void rejectsMalformedAndMixedFamilyValues() {
        assertFalse(CidrMatcher.matches("203.0.113.0/99", "203.0.113.1"));
        assertFalse(CidrMatcher.matches("bad", "203.0.113.1"));
        assertFalse(CidrMatcher.matches("203.0.113.0/24", "::1"));
    }
}
