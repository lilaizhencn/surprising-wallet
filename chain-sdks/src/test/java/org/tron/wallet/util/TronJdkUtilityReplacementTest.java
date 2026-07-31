package org.tron.wallet.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Tron 工具类移除 Commons Lang 后的 JDK 判断与原有边界语义一致。
 */
class TronJdkUtilityReplacementTest {

    /**
     * 验证空字节数组和空字符串的 JDK 判断保持原有返回值。
     */
    @Test
    void shouldKeepEmptyByteAndStringSemantics() {
        assertEquals(0, ByteArray.toLong(null));
        assertEquals(0, ByteArray.toLong(new byte[0]));
        assertEquals(0, ByteArray.toInt(null));
        assertEquals(0, ByteArray.toInt(new byte[0]));
        assertNull(ByteArray.fromString(null));
        assertNull(ByteArray.fromString(""));
        assertNull(ByteArray.fromString(" \t\n"));
        assertNull(ByteArray.toStr(null));
        assertNull(ByteArray.toStr(new byte[0]));
    }

    /**
     * 验证非空字节转换仍然保持原有数据内容。
     */
    @Test
    void shouldKeepNonEmptyByteConversion() {
        byte[] value = "tron".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(value, ByteArray.fromString("tron"));
        assertEquals("tron", ByteArray.toStr(value));
    }

    /**
     * 验证全小写字符判断只接受非空且每个字符都是小写字符的字符串。
     */
    @Test
    void shouldPreserveAllLowerCaseSemantics() {
        assertTrue(JsonFormat.isAllLowerCase("transfercontract"));
        assertFalse(JsonFormat.isAllLowerCase(null));
        assertFalse(JsonFormat.isAllLowerCase(""));
        assertFalse(JsonFormat.isAllLowerCase("transfercontract1"));
        assertFalse(JsonFormat.isAllLowerCase("transfer-contract"));
        assertFalse(JsonFormat.isAllLowerCase("TransferContract"));
    }
}
