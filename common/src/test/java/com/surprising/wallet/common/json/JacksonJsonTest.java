package com.surprising.wallet.common.json;

import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Jackson 3 动态 JSON 工具覆盖的对象转换、数组转换和字段读写语义。
 */
class JacksonJsonTest {

    /** Jackson 3 测试对象映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证签名元数据中的业务对象数组可以保持顺序并恢复为领域对象。
     */
    @Test
    void convertsDomainArraysWithoutChangingValues() {
        UtxoTransaction utxo = UtxoTransaction.builder()
                .txId("tx-1")
                .seq((short) 2)
                .balance(new BigDecimal("1.23000000"))
                .build();
        Address address = Address.builder()
                .address("addr-1")
                .userId(7L)
                .index(3)
                .build();
        WithdrawRecord record = WithdrawRecord.builder()
                .withdrawId("withdraw-1")
                .balance(new BigDecimal("0.12000000"))
                .build();

        ObjectNode signature = objectMapper.createObjectNode();
        signature.set("utxos", objectMapper.valueToTree(List.of(utxo)));
        signature.set("addresses", objectMapper.valueToTree(List.of(address)));
        signature.set("withdraw", objectMapper.valueToTree(List.of(record)));
        signature.put("feeRate", 10L);

        ObjectNode parsed = JacksonJson.readObject(
                objectMapper, JacksonJson.writeValue(objectMapper, signature));
        List<UtxoTransaction> parsedUtxos = JacksonJson.toList(
                objectMapper, parsed.get("utxos"), UtxoTransaction.class);
        List<Address> parsedAddresses = JacksonJson.toList(
                objectMapper, parsed.get("addresses"), Address.class);
        List<WithdrawRecord> parsedRecords = JacksonJson.toList(
                objectMapper, parsed.get("withdraw"), WithdrawRecord.class);

        assertEquals("tx-1", parsedUtxos.getFirst().getTxId());
        assertEquals(0, new BigDecimal("1.23000000").compareTo(parsedUtxos.getFirst().getBalance()));
        assertEquals("addr-1", parsedAddresses.getFirst().getAddress());
        assertEquals("withdraw-1", parsedRecords.getFirst().getWithdrawId());
        assertEquals(10L, JacksonJson.longValue(parsed, "feeRate"));
    }

    /**
     * 验证动态字段读取和缺失数组处理不会把缺失值误判为业务值。
     */
    @Test
    void handlesMissingAndTypedFieldsExplicitly() {
        ObjectNode signature = objectMapper.createObjectNode();
        signature.put("valid", false);
        signature.put("error", "invalid signature");

        assertFalse(JacksonJson.booleanValue(signature, "missing"));
        assertTrue(signature.has("valid"));
        assertFalse(JacksonJson.booleanValue(signature, "valid"));
        assertEquals("invalid signature", JacksonJson.text(signature, "error"));
        assertNull(JacksonJson.text(signature, "missing"));
        assertNull(JacksonJson.toList(objectMapper, signature.get("withdraw"), String.class));
    }
}
