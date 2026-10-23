package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.service.CustodyAddressService;

/**
 * 验证 {@code CustodyAddressServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyAddressServiceTest {

    /**
     * 验证 {@code addressVersionDefaultsToZeroAndRejectsInvalidValues} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void addressVersionDefaultsToZeroAndRejectsInvalidValues() {
        assertEquals(0L, CustodyAddressService.requireAddressVersion(null));
        assertEquals(7L, CustodyAddressService.requireAddressVersion(7L));
        assertThrows(IllegalArgumentException.class,
                () -> CustodyAddressService.requireAddressVersion(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> CustodyAddressService.requireAddressVersion((long) Integer.MAX_VALUE + 1));
    }

    /**
     * 验证 {@code tenantSubjectsCannotUseReservedSystemPrefix} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tenantSubjectsCannotUseReservedSystemPrefix() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CustodyAddressService.requireSubject("__sw_gas_reserve__:eth", false));

        assertTrue(error.getMessage().contains("reserved"));
        assertEquals("__sw_gas_reserve__:eth",
                CustodyAddressService.requireSubject("__sw_gas_reserve__:eth", true));
    }
}
