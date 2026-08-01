package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.service.CustodyPasswordService;

/**
 * 验证 {@code CustodyPasswordServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyPasswordServiceTest {
    /**
     * 保存 {@code passwords}，用于测试签名、认证或密钥相关逻辑。
     */
    private final CustodyPasswordService passwords = new CustodyPasswordService();

    /**
     * 验证 {@code hashesWithUniqueSaltAndVerifies} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void hashesWithUniqueSaltAndVerifies() {
        String first = passwords.hash("a-strong-password-123");
        String second = passwords.hash("a-strong-password-123");

        assertNotEquals(first, second);
        assertTrue(passwords.verify("a-strong-password-123", first));
        assertFalse(passwords.verify("wrong-password-123", first));
        assertFalse(passwords.verify("a-strong-password-123", "invalid"));
    }

    /**
     * 验证 {@code rejectsWeakPassword} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void rejectsWeakPassword() {
        assertThrows(IllegalArgumentException.class, () -> passwords.hash("too-short"));
    }
}
