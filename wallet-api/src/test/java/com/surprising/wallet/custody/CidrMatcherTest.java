package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.model.CidrMatcher;

/**
 * 验证 {@code CidrMatcherTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CidrMatcherTest {
    /**
     * 验证 {@code matchesIpv4AndIpv6Networks} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void matchesIpv4AndIpv6Networks() {
        assertTrue(CidrMatcher.matches("203.0.113.0/24", "203.0.113.42"));
        assertFalse(CidrMatcher.matches("203.0.113.0/24", "203.0.114.42"));
        assertTrue(CidrMatcher.matches("2001:db8::/32", "2001:db8:12::1"));
        assertFalse(CidrMatcher.matches("2001:db8::/48", "2001:db9::1"));
    }

    /**
     * 验证 {@code rejectsMalformedAndMixedFamilyValues} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void rejectsMalformedAndMixedFamilyValues() {
        assertFalse(CidrMatcher.matches("203.0.113.0/99", "203.0.113.1"));
        assertFalse(CidrMatcher.matches("bad", "203.0.113.1"));
        assertFalse(CidrMatcher.matches("203.0.113.0/24", "::1"));
    }
}
